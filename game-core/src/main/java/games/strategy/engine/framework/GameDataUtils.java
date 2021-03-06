package games.strategy.engine.framework;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.logging.Level;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameObjectOutputStream;
import games.strategy.engine.history.History;
import games.strategy.io.IoUtils;
import lombok.extern.java.Log;

/**
 * A collection of useful methods for working with instances of {@link GameData}.
 */
@Log
public final class GameDataUtils {
  private GameDataUtils() {}

  /**
   * Create a deep copy of GameData without history as it can get large.
   * <strong>You should have the game data's write lock before calling this method</strong>
   */
  public static GameData cloneGameDataWithoutHistory(final GameData data, final boolean copyDelegates) {
    final History temp = data.getHistory();
    data.resetHistory();
    final GameData dataCopy = cloneGameData(data, copyDelegates);
    data.setHistory(temp);
    return dataCopy;
  }

  public static GameData cloneGameData(final GameData data) {
    return cloneGameData(data, false);
  }

  /**
   * Create a deep copy of GameData.
   * <strong>You should have the game data's read or write lock before calling this method</strong>
   */
  public static GameData cloneGameData(final GameData data, final boolean copyDelegates) {
    try {
      final byte[] bytes = IoUtils.writeToMemory(os -> GameDataManager.saveGame(os, data, copyDelegates));
      return IoUtils.readFromMemory(bytes, GameDataManager::loadGame);
    } catch (final IOException e) {
      log.log(Level.SEVERE, "Failed to clone game data", e);
      return null;
    }
  }

  /**
   * Translate units, territories and other game data objects from one game data into another.
   */
  @SuppressWarnings("unchecked")
  public static <T> T translateIntoOtherGameData(final T object, final GameData translateInto) {
    try {
      final byte[] bytes = IoUtils.writeToMemory(os -> {
        try (ObjectOutputStream out = new GameObjectOutputStream(os)) {
          out.writeObject(object);
        }
      });
      return IoUtils.readFromMemory(bytes, is -> {
        final GameObjectStreamFactory factory = new GameObjectStreamFactory(translateInto);
        try (ObjectInputStream in = factory.create(is)) {
          return (T) in.readObject();
        } catch (final ClassNotFoundException e) {
          throw new IOException(e);
        }
      });
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }
}
