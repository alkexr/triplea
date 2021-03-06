package games.strategy.engine.framework.startup.ui.panels.main;

import java.util.Optional;

import javax.swing.JOptionPane;

import org.triplea.game.startup.SetupModel;

import games.strategy.engine.framework.startup.mc.GameSelectorModel;
import games.strategy.engine.framework.startup.mc.SetupPanelModel;
import games.strategy.engine.framework.startup.ui.panels.main.game.selector.GameSelectorPanel;
import games.strategy.engine.framework.ui.background.WaitWindow;

/**
 * Can be used to create a {@link MainPanel} UI component class, which is a UI holder. The final contents are added to
 * {@link MainPanel} and we set up listeners so that we can change screens by swapping the contents rendered by
 * {@link MainPanel}.
 */
public class MainPanelBuilder {

  /**
   * Creates a MainPanel instance and configures screen transition listeners.
   */
  public MainPanel buildMainPanel(
      final SetupPanelModel setupPanelModel,
      final GameSelectorModel gameSelectorModel) {
    final GameSelectorPanel gameSelectorPanel = new GameSelectorPanel(gameSelectorModel);
    gameSelectorModel.addObserver(gameSelectorPanel);

    final MainPanel mainPanel = new MainPanel(
        gameSelectorPanel,
        uiPanel -> {
          setupPanelModel.getPanel().getLauncher()
              .ifPresent(launcher -> {
                final WaitWindow gameLoadingWindow = new WaitWindow();
                gameLoadingWindow.setLocationRelativeTo(JOptionPane.getFrameForComponent(uiPanel));
                gameLoadingWindow.setVisible(true);
                gameLoadingWindow.showWait();
                JOptionPane.getFrameForComponent(uiPanel).setVisible(false);
                new Thread(() -> {
                  try {
                    launcher.launch(uiPanel);
                  } finally {
                    gameLoadingWindow.doneWait();
                  }
                }).start();
              });
          setupPanelModel.getPanel().postStartGame();
        },
        () -> Optional.ofNullable(setupPanelModel.getPanel())
            .map(SetupModel::getChatModel),
        setupPanelModel::showSelectType);
    setupPanelModel.setPanelChangeListener(mainPanel);
    gameSelectorModel.addObserver(mainPanel);
    return mainPanel;
  }
}
