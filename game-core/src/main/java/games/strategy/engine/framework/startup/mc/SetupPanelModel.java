package games.strategy.engine.framework.startup.mc;

import java.awt.Dimension;
import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.triplea.game.startup.ServerSetupModel;

import com.google.common.base.Preconditions;

import games.strategy.engine.framework.GameRunner;
import games.strategy.engine.framework.startup.ui.ClientSetupPanel;
import games.strategy.engine.framework.startup.ui.LocalSetupPanel;
import games.strategy.engine.framework.startup.ui.MetaSetupPanel;
import games.strategy.engine.framework.startup.ui.PbemSetupPanel;
import games.strategy.engine.framework.startup.ui.ServerSetupPanel;
import games.strategy.engine.framework.startup.ui.SetupPanel;
import games.strategy.engine.lobby.client.login.LobbyLogin;
import games.strategy.engine.lobby.client.login.LobbyPropertyFetcherConfiguration;
import games.strategy.engine.lobby.client.ui.LobbyFrame;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * This class provides a way to switch between different ISetupPanel displays.
 */
@RequiredArgsConstructor
public class SetupPanelModel implements ServerSetupModel {
  @Getter
  protected final GameSelectorModel gameSelectorModel;
  protected SetupPanel panel = null;

  @Setter
  private Consumer<SetupPanel> panelChangeListener;
  @Nonnull
  private final JFrame ui;


  @Override
  public void showSelectType() {
    setGameTypePanel(new MetaSetupPanel(this));
  }

  public void showLocal() {
    setGameTypePanel(new LocalSetupPanel(gameSelectorModel));
  }

  public void showPbem() {
    setGameTypePanel(new PbemSetupPanel(gameSelectorModel));
  }

  /**
   * Starts the game server and displays the game start screen afterwards, awaiting remote game clients.
   */
  public void showServer() {
    new ServerModel(gameSelectorModel, this, ui).createServerMessenger();
  }

  @Override
  public void onServerMessengerCreated(final ServerModel serverModel) {
    SwingUtilities.invokeLater(() -> {
      setGameTypePanel(new ServerSetupPanel(serverModel, gameSelectorModel));
      // for whatever reason, the server window is showing very very small, causing the nation info to be cut and
      // requiring scroll bars
      final int x = (ui.getPreferredSize().width > 800 ? ui.getPreferredSize().width : 800);
      final int y = (ui.getPreferredSize().height > 660 ? ui.getPreferredSize().height : 660);
      ui.setPreferredSize(new Dimension(x, y));
      ui.setSize(new Dimension(x, y));
    });
  }

  /**
   * A method that establishes a connection to a remote game and displays the game start screen afterwards if the
   * connection was successfully established.
   */
  public void showClient() {
    Preconditions.checkState(!SwingUtilities.isEventDispatchThread());
    final ClientModel model = new ClientModel(gameSelectorModel, this);
    if (model.createClientMessenger(ui)) {
      SwingUtilities.invokeLater(() -> setGameTypePanel(new ClientSetupPanel(model)));
    } else {
      model.cancel();
    }
  }

  private void setGameTypePanel(final SetupPanel panel) {
    if (this.panel != null) {
      this.panel.cancel();
    }
    this.panel = panel;

    Optional.ofNullable(panelChangeListener)
        .ifPresent(listener -> listener.accept(panel));
  }

  public SetupPanel getPanel() {
    return panel;
  }

  /**
   * Executes a login sequence prompting the user for their lobby username+password and sends it to
   * server. If successful the user is presented with the lobby frame. Failure cases are handled and
   * user is presented with another try or they can abort. In the abort case this method is a no-op.
   */
  public void login() {
    LobbyPropertyFetcherConfiguration.lobbyServerPropertiesFetcher().fetchLobbyServerProperties()
        .ifPresent(lobbyServerProperties -> {
          final LobbyLogin login = new LobbyLogin(ui, lobbyServerProperties);

          Optional.ofNullable(login.login())
              .ifPresent(
                  lobbyClient -> {
                    final LobbyFrame lobbyFrame = new LobbyFrame(lobbyClient, lobbyServerProperties);
                    GameRunner.hideMainFrame();
                    lobbyFrame.setVisible(true);
                  });
        });
  }
}
