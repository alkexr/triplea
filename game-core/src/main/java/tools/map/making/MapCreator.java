package tools.map.making;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.File;
import java.util.function.Consumer;

import javax.swing.Action;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

import org.triplea.awt.OpenFileUtility;
import org.triplea.java.Interruptibles;
import org.triplea.swing.SwingAction;

import games.strategy.triplea.UrlConstants;
import games.strategy.triplea.image.UnitImageFactory;
import tools.image.AutoPlacementFinder;
import tools.image.CenterPicker;
import tools.image.DecorationPlacer;
import tools.image.FileSave;
import tools.image.PolygonGrabber;
import tools.image.ReliefImageBreaker;
import tools.image.TileImageBreaker;
import tools.image.TileImageReconstructor;
import tools.util.ToolArguments;

/**
 * A frame that will run the different map making utilities we have.
 */
public class MapCreator extends JFrame {
  private static final long serialVersionUID = 3593102638082774498L;
  private static long memoryInBytes = Runtime.getRuntime().maxMemory();
  private static File mapFolderLocation = null;
  private static double unitZoom = 0.75;
  private static int unitWidth = UnitImageFactory.DEFAULT_UNIT_ICON_SIZE;
  private static int unitHeight = UnitImageFactory.DEFAULT_UNIT_ICON_SIZE;

  private final JPanel mainPanel;
  private final JPanel panel1 = new JPanel();
  private final JPanel panel2 = new JPanel();
  private final JPanel panel3 = new JPanel();
  private final JPanel panel4 = new JPanel();

  private MapCreator() {
    super("TripleA Map Creator");
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    // components
    mainPanel = new JPanel();
    final JPanel sidePanel = new JPanel();
    final JButton part1 = new JButton("Step 1: Map Properties");
    final JButton part2 = new JButton("Step 2: Map Utilities");
    final JButton part3 = new JButton("Step 3: Game XML");
    final JButton part4 = new JButton("Other: Optional Things");
    sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.PAGE_AXIS));
    sidePanel.add(Box.createVerticalGlue());
    sidePanel.add(part1);
    part1.setAlignmentX(Component.CENTER_ALIGNMENT);
    sidePanel.add(Box.createVerticalGlue());
    sidePanel.add(part2);
    part2.setAlignmentX(Component.CENTER_ALIGNMENT);
    sidePanel.add(Box.createVerticalGlue());
    sidePanel.add(part3);
    part3.setAlignmentX(Component.CENTER_ALIGNMENT);
    sidePanel.add(Box.createVerticalGlue());
    sidePanel.add(part4);
    part4.setAlignmentX(Component.CENTER_ALIGNMENT);
    sidePanel.add(Box.createVerticalGlue());
    createPart1Panel();
    createPart2Panel();
    createPart3Panel();
    createPart4Panel();
    part1.addActionListener(SwingAction.of("Part 1", e -> setupMainPanel(panel1)));
    part2.addActionListener(SwingAction.of("Part 2", e -> setupMainPanel(panel2)));
    part3.addActionListener(SwingAction.of("Part 3", e -> setupMainPanel(panel3)));
    part4.addActionListener(SwingAction.of("Part 4", e -> setupMainPanel(panel4)));
    // set up the menu actions
    final Action closeAction = SwingAction.of("Close", e -> dispose());
    closeAction.putValue(Action.SHORT_DESCRIPTION, "Close Window");
    // set up the menu items
    final JMenuItem exitItem = new JMenuItem(closeAction);
    // set up the menu bar
    final JMenuBar menuBar = new JMenuBar();
    setJMenuBar(menuBar);
    final JMenu fileMenu = new JMenu("File");
    fileMenu.setMnemonic('F');
    fileMenu.addSeparator();
    fileMenu.add(exitItem);
    menuBar.add(fileMenu);
    // set up the layout manager
    this.getContentPane().setLayout(new BorderLayout());
    this.getContentPane().add(new JScrollPane(sidePanel), BorderLayout.WEST);
    this.getContentPane().add(new JScrollPane(mainPanel), BorderLayout.CENTER);
    // now set up the main screen
    setupMainPanel(panel1);
  }

  private void setupMainPanel(final JPanel panel) {
    mainPanel.removeAll();
    mainPanel.add(panel);
    setWidgetActivation();
  }

  private void setWidgetActivation() {
    SwingAction.invokeNowOrLater(() -> {
      mainPanel.validate();
      mainPanel.repaint();
      this.validate();
      this.repaint();
    });
  }

  private void createPart1Panel() {
    panel1.removeAll();
    panel1.setLayout(new BoxLayout(panel1, BoxLayout.PAGE_AXIS));
    panel1.add(Box.createVerticalStrut(30));
    final JTextArea text = new JTextArea(12, 10);
    text.setWrapStyleWord(true);
    text.setLineWrap(true);
    text.setText("Welcome to Veqryn's map creator program for TripleA."
        + "\r\nThis program just runs utilities inside the triplea.jar file for you, and you could easily "
        + "run them yourself from the command line by reading the docs/developer_documentation.html"
        + "\r\n\r\nBefore you begin, go create a folder in your directory: Users\\yourname\\triplea\\maps"
        + "\r\nName the folder with a short name of your map, do not use any special characters in the name."
        + "\r\nNext, create 5 folders inside your map folder, with these names: "
        + "flags, units, baseTiles, reliefTiles, games"
        + "\r\nThen, create a text file and rename it \"map.properties\" or use one created by this utility."
        + "\r\n\r\nTo start the Map Utilities, have a png image of your map with just the territory borders "
        + "and nothing else. The borders must be in black (hex: 000000) and there should not be any "
        + "anti-aliasing (smoothing) of the lines or edges that stick out."
        + "\r\nCreate a small image of the map (approx 250 pixels wide) and name it \"smallMap.jpeg\"."
        + "\r\nPut these in the map's root folder. You can now start the map maker by clicking and filling "
        + "in the details below, before moving on to 'Step 2' and running the map utilities.");
    final JScrollPane scrollText = new JScrollPane(text);
    panel1.add(scrollText);
    panel1.add(Box.createVerticalStrut(30));
    panel1.add(new JLabel("Click button open up the readme file on how to make maps:"));
    final JButton helpButton = new JButton("Start Tutorial  /  Show Help Document");
    helpButton.addActionListener(e -> OpenFileUtility.openUrl(UrlConstants.MAP_MAKER_HELP));
    panel1.add(helpButton);
    panel1.add(Box.createVerticalStrut(30));
    panel1.add(new JLabel("Click button to select where your map folder is:"));
    final JButton mapFolderButton = new JButton("Select Map Folder");
    mapFolderButton.addActionListener(SwingAction.of("Select Map Folder", e -> {
      final String path = new FileSave("Where is your map's folder?", null, mapFolderLocation).getPathString();
      if (path != null) {
        final File mapFolder = new File(path);
        if (mapFolder.exists()) {
          mapFolderLocation = mapFolder;
          System.setProperty(ToolArguments.MAP_FOLDER, mapFolderLocation.getPath());
        }
      }
    }));
    panel1.add(mapFolderButton);
    panel1.add(Box.createVerticalStrut(30));
    panel1.add(new JLabel("Set the unit scaling (unit image zoom): "));
    panel1.add(new JLabel("Choose one of: 1.25, 1, 0.875, 0.8333, 0.75, 0.6666, 0.5625, 0.5"));
    final JTextField unitZoomText = new JTextField("" + unitZoom);
    unitZoomText.setMaximumSize(new Dimension(100, 20));
    unitZoomText.addFocusListener(new FocusListener() {
      @Override
      public void focusGained(final FocusEvent e) {}

      @Override
      public void focusLost(final FocusEvent e) {
        try {
          unitZoom = Math.min(4.0, Math.max(0.1, Double.parseDouble(unitZoomText.getText())));
          System.setProperty(ToolArguments.UNIT_ZOOM, "" + unitZoom);
        } catch (final Exception ex) {
          // ignore malformed input
        }
        unitZoomText.setText("" + unitZoom);
      }
    });
    panel1.add(unitZoomText);
    panel1.add(Box.createVerticalStrut(30));
    panel1.add(new JLabel("Set the width of the unit images: "));
    final JTextField unitWidthText = new JTextField("" + unitWidth);
    unitWidthText.setMaximumSize(new Dimension(100, 20));
    unitWidthText.addFocusListener(new FocusListener() {
      @Override
      public void focusGained(final FocusEvent e) {}

      @Override
      public void focusLost(final FocusEvent e) {
        try {
          unitWidth = Math.min(400, Math.max(1, Integer.parseInt(unitWidthText.getText())));
          System.setProperty(ToolArguments.UNIT_WIDTH, "" + unitWidth);
        } catch (final Exception ex) {
          // ignore malformed input
        }
        unitWidthText.setText("" + unitWidth);
      }
    });
    panel1.add(unitWidthText);
    panel1.add(Box.createVerticalStrut(30));
    panel1.add(new JLabel("Set the height of the unit images: "));
    final JTextField unitHeightText = new JTextField("" + unitHeight);
    unitHeightText.setMaximumSize(new Dimension(100, 20));
    unitHeightText.addFocusListener(new FocusListener() {
      @Override
      public void focusGained(final FocusEvent e) {}

      @Override
      public void focusLost(final FocusEvent e) {
        try {
          unitHeight = Math.min(400, Math.max(1, Integer.parseInt(unitHeightText.getText())));
          System.setProperty(ToolArguments.UNIT_HEIGHT, "" + unitHeight);
        } catch (final Exception ex) {
          // ignore malformed input
        }
        unitHeightText.setText("" + unitHeight);
      }
    });
    panel1.add(unitHeightText);
    panel1.add(Box.createVerticalStrut(30));
    panel1
        .add(new JLabel("<html>Here you can set the 'max memory' that utilities like the Polygon Grabber will use.<br>"
            + "This is useful is you have a very large map, or ever get any Java Heap Space errors.</html>"));
    panel1.add(new JLabel("Set the amount of memory to use when running new processes (in megabytes [mb]):"));
    final JTextField memoryText = new JTextField("" + (memoryInBytes / (1024 * 1024)));
    memoryText.setMaximumSize(new Dimension(100, 20));
    memoryText.addFocusListener(new FocusListener() {
      @Override
      public void focusGained(final FocusEvent e) {}

      @Override
      public void focusLost(final FocusEvent e) {
        try {
          memoryInBytes = (long) 1024 * 1024 * Math.min(4096, Math.max(256, Integer.parseInt(memoryText.getText())));
        } catch (final Exception ex) {
          // ignore malformed input
        }
        memoryText.setText("" + (memoryInBytes / (1024 * 1024)));
      }
    });
    panel1.add(memoryText);
    panel1.add(Box.createVerticalStrut(30));
    panel1.validate();
  }

  private void createPart2Panel() {
    panel2.removeAll();
    panel2.setLayout(new BoxLayout(panel2, BoxLayout.PAGE_AXIS));
    panel2.add(Box.createVerticalStrut(30));
    panel2.add(new JLabel("Map Skin Utilities:"));
    panel2.add(Box.createVerticalStrut(30));
    final JButton mapPropertiesMakerButton = new JButton("Run the Map Properties Maker");
    mapPropertiesMakerButton
        .addActionListener(SwingAction.of("Run the Map Properties Maker", e -> runUtility(MapPropertiesMaker::run)));
    panel2.add(mapPropertiesMakerButton);
    panel2.add(Box.createVerticalStrut(30));
    final JButton centerPickerButton = new JButton("Run the Center Picker");
    centerPickerButton.addActionListener(SwingAction.of("Run the Center Picker", e -> runUtility(CenterPicker::run)));
    panel2.add(centerPickerButton);
    panel2.add(Box.createVerticalStrut(30));
    final JButton polygonGrabberButton = new JButton("Run the Polygon Grabber");
    polygonGrabberButton
        .addActionListener(SwingAction.of("Run the Polygon Grabber", e -> runUtility(PolygonGrabber::run)));
    panel2.add(polygonGrabberButton);
    panel2.add(Box.createVerticalStrut(30));
    final JButton autoPlacerButton = new JButton("Run the Automatic Placement Finder");
    autoPlacerButton.addActionListener(
        SwingAction.of("Run the Automatic Placement Finder", e -> runUtility(AutoPlacementFinder::run)));
    panel2.add(autoPlacerButton);
    panel2.add(Box.createVerticalStrut(30));
    final JButton placementPickerButton = new JButton("Run the Placement Picker");
    placementPickerButton
        .addActionListener(SwingAction.of("Run the Placement Picker", e -> runUtility(PlacementPicker::run)));
    panel2.add(placementPickerButton);
    panel2.add(Box.createVerticalStrut(30));
    final JButton tileBreakerButton = new JButton("Run the Tile Image Breaker");
    tileBreakerButton
        .addActionListener(SwingAction.of("Run the Tile Image Breaker", e -> runUtility(TileImageBreaker::run)));
    panel2.add(tileBreakerButton);
    panel2.add(Box.createVerticalStrut(30));
    final JButton decorationPlacerButton = new JButton("Run the Decoration Placer");
    decorationPlacerButton
        .addActionListener(SwingAction.of("Run the Decoration Placer", e -> runUtility(DecorationPlacer::run)));
    panel2.add(decorationPlacerButton);
    panel2.add(Box.createVerticalStrut(30));
    panel2.validate();
  }

  private void createPart3Panel() {
    panel3.removeAll();
    panel3.setLayout(new BoxLayout(panel3, BoxLayout.PAGE_AXIS));
    panel3.add(Box.createVerticalStrut(30));
    panel3.add(new JLabel("Game XML Utilities:"));
    panel3.add(new JLabel("Sorry but for now the only XML creator is Wisconsin's 'Part 2' of his map maker."));
    panel3.add(Box.createVerticalStrut(30));
    final JButton connectionFinderButton = new JButton("Run the Connection Finder");
    connectionFinderButton
        .addActionListener(SwingAction.of("Run the Connection Finder", e -> runUtility(ConnectionFinder::run)));
    panel3.add(connectionFinderButton);
    panel3.add(Box.createVerticalStrut(30));
    panel3.validate();
  }

  private void createPart4Panel() {
    panel4.removeAll();
    panel4.setLayout(new BoxLayout(panel4, BoxLayout.PAGE_AXIS));
    panel4.add(Box.createVerticalStrut(30));
    panel4.add(new JLabel("Other or Optional Utilities:"));
    panel4.add(Box.createVerticalStrut(30));
    final JButton reliefBreakerButton = new JButton("Run the Relief Image Breaker");
    reliefBreakerButton
        .addActionListener(SwingAction.of("Run the Relief Image Breaker", e -> runUtility(ReliefImageBreaker::run)));
    panel4.add(reliefBreakerButton);
    panel4.add(Box.createVerticalStrut(30));
    final JButton imageShrinkerButton = new JButton("Run the Image Shrinker");
    imageShrinkerButton
        .addActionListener(SwingAction.of("Run the Image Shrinker", e -> runUtility(ImageShrinker::run)));
    panel4.add(imageShrinkerButton);
    panel4.add(Box.createVerticalStrut(30));
    final JButton tileImageReconstructorButton = new JButton("Run the Tile Image Reconstructor");
    tileImageReconstructorButton.addActionListener(
        SwingAction.of("Run the Tile Image Reconstructor", e -> runUtility(TileImageReconstructor::run)));
    panel4.add(tileImageReconstructorButton);
    panel4.add(Box.createVerticalStrut(30));
    panel4.validate();
  }

  private static void runUtility(final Consumer<String[]> entryPoint) {
    entryPoint.accept(new String[0]);
  }

  /**
   * Opens a map creator window.
   */
  public static void openMapCreatorWindow() {
    Interruptibles.await(() -> SwingAction.invokeAndWait(() -> {
      final MapCreator creator = new MapCreator();
      creator.setSize(800, 600);
      creator.setLocationRelativeTo(null);
      creator.setVisible(true);
    }));
  }
}
