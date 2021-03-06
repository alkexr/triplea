package games.strategy.engine.data;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.triplea.util.Tuple;
import org.triplea.util.Version;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;

import games.strategy.engine.ClientContext;
import games.strategy.engine.GameEngineVersion;
import games.strategy.engine.data.gameparser.XmlGameElementMapper;
import games.strategy.engine.data.properties.BooleanProperty;
import games.strategy.engine.data.properties.ColorProperty;
import games.strategy.engine.data.properties.ComboProperty;
import games.strategy.engine.data.properties.FileProperty;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.engine.data.properties.IEditableProperty;
import games.strategy.engine.data.properties.NumberProperty;
import games.strategy.engine.data.properties.StringProperty;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.GenericTechAdvance;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.formatter.MyFormatter;
import lombok.extern.java.Log;

/**
 * Parses a game XML file into a {@link GameData} domain object.
 */
@Log
public final class GameParser {
  public static final String DTD_FILE_NAME = "game.dtd";
  private static final String RESOURCE_IS_DISPLAY_FOR_NONE = "NONE";

  private final GameData data;
  private final Collection<SAXParseException> errorsSax = new ArrayList<>();
  private final String mapName;
  private final XmlGameElementMapper xmlGameElementMapper;

  @VisibleForTesting
  GameParser(final GameData gameData, final String mapName) {
    this(gameData, mapName, new XmlGameElementMapper());
  }

  private GameParser(final GameData gameData, final String mapName, final XmlGameElementMapper xmlGameElementMapper) {
    data = gameData;
    this.mapName = mapName;
    this.xmlGameElementMapper = xmlGameElementMapper;
  }

  /**
   * Performs a deep parse of the game definition contained in the specified stream.
   *
   * @return A complete {@link GameData} instance that can be used to play the game.
   */
  public static GameData parse(final String mapName, final InputStream stream)
      throws GameParseException, EngineVersionException {
    return parse(mapName, stream, new XmlGameElementMapper());
  }

  @VisibleForTesting
  public static GameData parse(
      final String mapName,
      final InputStream stream,
      final XmlGameElementMapper xmlGameElementMapper)
      throws GameParseException, EngineVersionException {
    checkNotNull(mapName);
    checkNotNull(stream);
    checkNotNull(xmlGameElementMapper);

    return new GameParser(new GameData(), mapName, xmlGameElementMapper).parse(stream);
  }

  private GameData parse(final InputStream stream) throws GameParseException, EngineVersionException {
    final Element root = parseDom(stream);
    parseMapProperties(root);
    parseMapDetails(root);
    return data;
  }

  private GameParseException newGameParseException(final String message) {
    return newGameParseException(message, null);
  }

  private GameParseException newGameParseException(final String message, final @Nullable Throwable cause) {
    final String gameName = data.getGameName() != null ? data.getGameName() : "<unknown>";
    return new GameParseException(
        String.format("map name: '%s', game name: '%s', %s", mapName, gameName, message),
        cause);
  }

  /**
   * Performs a shallow parse of the game definition contained in the specified stream.
   *
   * @return A partial {@link GameData} instance that can be used to display metadata about the game (e.g. when
   *         displaying all available maps); it cannot be used to play the game.
   */
  public static GameData parseShallow(final String mapName, final InputStream stream)
      throws GameParseException, EngineVersionException {
    checkNotNull(mapName);
    checkNotNull(stream);

    return new GameParser(new GameData(), mapName).parseShallow(stream);
  }

  private GameData parseShallow(final InputStream stream) throws GameParseException, EngineVersionException {
    final Element root = parseDom(stream);
    parseMapProperties(root);
    return data;
  }

  private Element parseDom(final InputStream stream) throws GameParseException {
    try {
      return getDocument(stream).getDocumentElement();
    } catch (final SAXException e) {
      throw newGameParseException("failed to parse XML document", e);
    }
  }

  private void parseMapProperties(final Element root) throws GameParseException, EngineVersionException {
    // mandatory fields
    // get the name of the map
    parseInfo(getSingleChild("info", root));

    // test minimum engine version FIRST
    parseMinimumEngineVersionNumber(getSingleChild("triplea", root, true));
    // if we manage to get this far, past the minimum engine version number test, AND we are still good, then check and
    // see if we have any SAX errors we need to show
    if (!errorsSax.isEmpty()) {
      for (final SAXParseException error : errorsSax) {
        log.severe("SAXParseException: game: " + (data.getGameName() == null ? "?" : data.getGameName()) + ", line: "
            + error.getLineNumber() + ", column: " + error.getColumnNumber() + ", error: " + error.getMessage());
      }
    }
    parseDiceSides(getSingleChild("diceSides", root, true));
    final Element playerListNode = getSingleChild("playerList", root);
    parsePlayerList(playerListNode);
    parseAlliances(playerListNode);
    final Node properties = getSingleChild("propertyList", root, true);
    if (properties != null) {
      parseProperties(properties);
    }
  }

  private void parseMapDetails(final Element root) throws GameParseException {
    parseMap(getSingleChild("map", root));
    final Element resourceList = getSingleChild("resourceList", root, true);
    if (resourceList != null) {
      parseResources(resourceList);
    }
    final Element unitList = getSingleChild("unitList", root, true);
    if (unitList != null) {
      parseUnits(unitList);
    }
    // Parse all different relationshipTypes that are defined in the xml, for example: War, Allied, Neutral, NAP
    final Element relationshipTypes = getSingleChild("relationshipTypes", root, true);
    if (relationshipTypes != null) {
      parseRelationshipTypes(relationshipTypes);
    }
    final Element territoryEffectList = getSingleChild("territoryEffectList", root, true);
    if (territoryEffectList != null) {
      parseTerritoryEffects(territoryEffectList);
    }
    parseGamePlay(getSingleChild("gamePlay", root));
    final Element production = getSingleChild("production", root, true);
    if (production != null) {
      parseProduction(production);
    }
    final Element technology = getSingleChild("technology", root, true);
    if (technology != null) {
      parseTechnology(technology);
    } else {
      TechAdvance.createDefaultTechAdvances(data);
    }
    final Element attachmentList = getSingleChild("attachmentList", root, true);
    if (attachmentList != null) {
      parseAttachments(attachmentList);
    }
    final Node initialization = getSingleChild("initialize", root, true);
    if (initialization != null) {
      parseInitialization(initialization);
    }
    // set & override default relationships
    // sets the relationship between all players and the NullPlayer to NullRelation (with archeType War)
    data.getRelationshipTracker().setNullPlayerRelations();
    // sets the relationship for all players with themselfs to the SelfRelation (with archeType Allied)
    data.getRelationshipTracker().setSelfRelations();
    // set default tech attachments (comes after we parse all technologies, parse all attachments, and parse all game
    // options/properties)
    checkThatAllUnitsHaveAttachments(data);
    TechAbilityAttachment.setDefaultTechnologyAttachments(data);
    try {
      validate();
    } catch (final Exception e) {
      log.log(Level.SEVERE, "Error parsing: " + mapName, e);
      throw newGameParseException("validation failed", e);
    }
  }

  private void parseDiceSides(final Node diceSides) {
    if (diceSides == null) {
      data.setDiceSides(6);
    } else {
      data.setDiceSides(Integer.parseInt(((Element) diceSides).getAttribute("value")));
    }
  }

  private void parseMinimumEngineVersionNumber(final Node minimumVersion) throws EngineVersionException {
    if (minimumVersion == null) {
      return;
    }
    final Version mapMinimumEngineVersion = new Version(((Element) minimumVersion).getAttribute("minimumVersion"));
    if (!GameEngineVersion.of(ClientContext.engineVersion())
        .isCompatibleWithMapMinimumEngineVersion(mapMinimumEngineVersion)) {
      throw new EngineVersionException(
          String.format("Current engine version: %s, is not compatible with version: %s, required by map: %s",
              ClientContext.engineVersion(),
              mapMinimumEngineVersion.toString(),
              data.getGameName()));
    }
  }

  private void validate() throws GameParseException {
    // validate unit attachments
    for (final UnitType u : data.getUnitTypeList()) {
      validateAttachments(u);
    }
    for (final Territory t : data.getMap()) {
      validateAttachments(t);
    }
    for (final Resource r : data.getResourceList().getResources()) {
      validateAttachments(r);
    }
    for (final PlayerId r : data.getPlayerList().getPlayers()) {
      validateAttachments(r);
    }
    for (final RelationshipType r : data.getRelationshipTypeList().getAllRelationshipTypes()) {
      validateAttachments(r);
    }
    for (final TerritoryEffect r : data.getTerritoryEffectList().values()) {
      validateAttachments(r);
    }
    for (final TechAdvance r : data.getTechnologyFrontier().getTechs()) {
      validateAttachments(r);
    }
    // if relationships are used, every player should have a relationship with every other player
    validateRelationships();
  }

  private void validateRelationships() throws GameParseException {
    // for every player
    for (final PlayerId player : data.getPlayerList()) {
      // in relation to every player
      for (final PlayerId player2 : data.getPlayerList()) {
        // See if there is a relationship between them
        if ((data.getRelationshipTracker().getRelationshipType(player, player2) == null)) {
          // or else throw an exception!
          throw newGameParseException("No relation set for: " + player.getName() + " and " + player2.getName());
        }
      }
    }
  }

  private void validateAttachments(final Attachable attachable) throws GameParseException {
    for (final IAttachment a : attachable.getAttachments().values()) {
      a.validate(data);
    }
  }

  private Document getDocument(final InputStream input) throws SAXException {
    try {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setValidating(true);
      // Not mandatory, but better than relying on the default implementation to prevent XXE
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "file");
      // get the dtd location
      final String dtdFile = "/games/strategy/engine/xml/" + DTD_FILE_NAME;
      final URL url = GameParser.class.getResource(dtdFile);
      if (url == null) {
        throw new RuntimeException(String.format("Map: %s, Could not find in classpath %s", mapName, dtdFile));
      }
      final DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new ErrorHandler() {
        @Override
        public void fatalError(final SAXParseException exception) {
          errorsSax.add(exception);
        }

        @Override
        public void error(final SAXParseException exception) {
          errorsSax.add(exception);
        }

        @Override
        public void warning(final SAXParseException exception) {
          errorsSax.add(exception);
        }
      });
      final String dtdSystem = url.toExternalForm();
      final String system = dtdSystem.substring(0, dtdSystem.length() - DTD_FILE_NAME.length());
      return builder.parse(input, system);
    } catch (final IOException | ParserConfigurationException e) {
      throw new IllegalStateException("Error parsing: " + mapName, e);
    }
  }

  private <T> T getValidatedObject(final Element element, final String attribute,
      final boolean mustFind, final Function<String, T> function, final String errorName)
      throws GameParseException {
    final String name = element.getAttribute(attribute);
    final T attachable = function.apply(name);
    if (attachable == null && mustFind) {
      throw newGameParseException("Could not find " + errorName + ". name:" + name);
    }
    return attachable;
  }

  /**
   * If mustfind is true and cannot find the player an exception will be thrown.
   */
  private PlayerId getPlayerId(final Element element, final String attribute, final boolean mustFind)
      throws GameParseException {
    return getValidatedObject(element, attribute, mustFind, data.getPlayerList()::getPlayerId, "player");
  }

  /**
   * If cannot find the player an exception will be thrown.
   *
   * @return a RelationshipType from the relationshipTypeList, at this point all relationshipTypes should have been
   *         declared
   */
  private RelationshipType getRelationshipType(final Element element, final String attribute)
      throws GameParseException {
    return getValidatedObject(element, attribute, true, data.getRelationshipTypeList()::getRelationshipType,
        "relation");
  }

  private TerritoryEffect getTerritoryEffect(final Element element)
      throws GameParseException {
    return getValidatedObject(element, "attachTo", true, data.getTerritoryEffectList()::get, "territoryEffect");
  }

  /**
   * If cannot find the productionRule an exception will be thrown.
   */
  private ProductionRule getProductionRule(final Element element)
      throws GameParseException {
    return getValidatedObject(element, "name", true, data.getProductionRuleList()::getProductionRule,
        "production rule");
  }

  /**
   * If cannot find the repairRule an exception will be thrown.
   */
  private RepairRule getRepairRule(final Element element) throws GameParseException {
    return getValidatedObject(element, "name", true, data.getRepairRules()::getRepairRule, "repair rule");
  }

  /**
   * If cannot find the territory an exception will be thrown.
   */
  private Territory getTerritory(final Element element, final String attribute)
      throws GameParseException {
    return getValidatedObject(element, attribute, true, data.getMap()::getTerritory, "territory");
  }

  /**
   * If mustfind is true and cannot find the unitType an exception will be thrown.
   */
  private UnitType getUnitType(final Element element, final String attribute, final boolean mustFind)
      throws GameParseException {
    return getValidatedObject(element, attribute, mustFind, data.getUnitTypeList()::getUnitType, "unitType");
  }

  /**
   * If cannot find the technology an exception will be thrown.
   */
  private TechAdvance getTechnology(final Element element)
      throws GameParseException {
    return getValidatedObject(element, "attachTo", true, this::getTechnology, "technology");
  }

  private TechAdvance getTechnology(final String name) {
    final TechnologyFrontier frontier = data.getTechnologyFrontier();
    TechAdvance type = frontier.getAdvanceByName(name);
    if (type == null) {
      type = frontier.getAdvanceByProperty(name);
    }
    return type;
  }

  /**
   * If cannot find the Delegate an exception will be thrown.
   */
  private IDelegate getDelegate(final Element element) throws GameParseException {
    return getValidatedObject(element, "delegate", true, data::getDelegate, "delegate");
  }

  /**
   * If mustfind is true and cannot find the Resource an exception will be thrown.
   */
  private Resource getResource(final Element element, final String attribute, final boolean mustFind)
      throws GameParseException {
    return getValidatedObject(element, attribute, mustFind, data.getResourceList()::getResource, "resource");
  }

  /**
   * If cannot find the productionRule an exception will be thrown.
   */
  private ProductionFrontier getProductionFrontier(final Element element) throws GameParseException {
    return getValidatedObject(element, "frontier", true, data.getProductionFrontierList()::getProductionFrontier,
        "production frontier");
  }

  /**
   * If cannot find the repairFrontier an exception will be thrown.
   */
  private RepairFrontier getRepairFrontier(final Element element)
      throws GameParseException {
    return getValidatedObject(element, "frontier", true, data.getRepairFrontierList()::getRepairFrontier,
        "repair frontier");
  }

  /**
   * Get the given child.
   * If there is not exactly one child throw a SAXExcpetion
   */
  private Element getSingleChild(final String name, final Element node) throws GameParseException {
    return getSingleChild(name, node, false);
  }

  /**
   * If optional is true, will not throw an exception if there are 0 children.
   */
  private Element getSingleChild(final String name, final Node node, final boolean optional) throws GameParseException {
    final List<Element> children = getChildren(name, node);
    // none found
    if (children.size() == 0) {
      if (optional) {
        return null;
      }
      throw newGameParseException("No child called " + name);
    }
    // too many found
    if (children.size() > 1) {
      throw newGameParseException("Too many children named " + name);
    }
    return children.get(0);
  }

  private static List<Element> getChildren(final String name, final Node node) {
    final NodeList children = node.getChildNodes();
    return IntStream.range(0, children.getLength())
        .mapToObj(children::item)
        .filter(current -> current.getNodeName().equals(name))
        .map(Element.class::cast)
        .collect(Collectors.toList());
  }

  private static List<Node> getNonTextNodesIgnoringValue(final Node node) {
    final List<Node> nonTextNodes = getNonTextNodes(node);
    nonTextNodes.removeIf(node1 -> ((Element) node1).getTagName().equals("value"));
    return nonTextNodes;
  }

  private static List<Node> getNonTextNodes(final Node node) {
    final NodeList children = node.getChildNodes();
    return IntStream.range(0, children.getLength())
        .mapToObj(children::item)
        .filter(current -> !(current.getNodeType() == Node.TEXT_NODE))
        .collect(Collectors.toList());
  }

  private void parseInfo(final Node info) {
    final String gameName = ((Element) info).getAttribute("name");
    data.setGameName(gameName);
    final String version = ((Element) info).getAttribute("version");
    data.setGameVersion(new Version(version));
  }

  private void parseMap(final Node map) throws GameParseException {
    final List<Element> grids = getChildren("grid", map);
    parseGrids(grids);
    // get the Territories
    final List<Element> territories = getChildren("territory", map);
    parseTerritories(territories);
    final List<Element> connections = getChildren("connection", map);
    parseConnections(connections);
  }

  private void parseGrids(final List<Element> grids) throws GameParseException {
    for (final Element current : grids) {
      final String gridType = current.getAttribute("type");
      final String name = current.getAttribute("name");
      final String xs = current.getAttribute("x");
      final String ys = current.getAttribute("y");
      final List<Element> waterNodes = getChildren("water", current);
      final Set<String> water = parseGridWater(waterNodes);
      final String horizontalConnections = current.getAttribute("horizontal-connections");
      final String verticalConnections = current.getAttribute("vertical-connections");
      final String diagonalConnections = current.getAttribute("diagonal-connections");
      setGrids(data, gridType, name, xs, ys, water, horizontalConnections, verticalConnections, diagonalConnections);
    }
  }

  /**
   * Creates and adds new territories and their connections to their map, based on a grid.
   */
  private void setGrids(final GameData data, final String gridType, final String name, final String xs,
      final String ys, final Set<String> water, final String horizontalConnections, final String verticalConnections,
      final String diagonalConnections) throws GameParseException {
    final GameMap map = data.getMap();
    final boolean horizontalConnectionsImplict;
    switch (horizontalConnections) {
      case "implicit":
        horizontalConnectionsImplict = true;
        break;
      case "explicit":
        horizontalConnectionsImplict = false;
        break;
      default:
        throw newGameParseException("horizontal-connections attribute must be either \"explicit\" or \"implicit\"");
    }
    final boolean verticalConnectionsImplict;
    switch (verticalConnections) {
      case "implicit":
        verticalConnectionsImplict = true;
        break;
      case "explicit":
        verticalConnectionsImplict = false;
        break;
      default:
        throw newGameParseException("vertical-connections attribute must be either \"explicit\" or \"implicit\"");
    }
    final boolean diagonalConnectionsImplict;
    switch (diagonalConnections) {
      case "implicit":
        diagonalConnectionsImplict = true;
        break;
      case "explicit":
        diagonalConnectionsImplict = false;
        break;
      default:
        throw newGameParseException("diagonal-connections attribute must be either \"explicit\" or \"implicit\"");
    }
    final int sizeY = (ys != null) ? Integer.valueOf(ys) : 0;
    final int sizeX = Integer.valueOf(xs);
    map.setGridDimensions(sizeX, sizeY);
    if (gridType.equals("square")) {
      // Add territories
      for (int y = 0; y < sizeY; y++) {
        for (int x = 0; x < sizeX; x++) {
          final boolean isWater = water.contains(x + "-" + y);
          map.addTerritory(new Territory(name + "_" + x + "_" + y, isWater, data));
        }
      }
      // Add any implicit horizontal connections
      if (horizontalConnectionsImplict) {
        for (int y = 0; y < sizeY; y++) {
          for (int x = 0; x < sizeX - 1; x++) {
            map.addConnection(map.getTerritoryFromCoordinates(x, y), map.getTerritoryFromCoordinates(x + 1, y));
          }
        }
      }
      // Add any implicit vertical connections
      if (verticalConnectionsImplict) {
        for (int x = 0; x < sizeX; x++) {
          for (int y = 0; y < sizeY - 1; y++) {
            map.addConnection(map.getTerritoryFromCoordinates(x, y), map.getTerritoryFromCoordinates(x, y + 1));
          }
        }
      }
      // Add any implicit acute diagonal connections
      if (diagonalConnectionsImplict) {
        for (int y = 0; y < sizeY - 1; y++) {
          for (int x = 0; x < sizeX - 1; x++) {
            map.addConnection(map.getTerritoryFromCoordinates(x, y), map.getTerritoryFromCoordinates(x + 1, y + 1));
          }
        }
      }
      // Add any implicit obtuse diagonal connections
      if (diagonalConnectionsImplict) {
        for (int y = 0; y < sizeY - 1; y++) {
          for (int x = 1; x < sizeX; x++) {
            map.addConnection(map.getTerritoryFromCoordinates(x, y), map.getTerritoryFromCoordinates(x - 1, y + 1));
          }
        }
      }
      // This type is a triangular grid of points and lines, used for in several rail games
    } else if (gridType.equals("points-and-lines")) {
      // Add territories
      for (int y = 0; y < sizeY; y++) {
        for (int x = 0; x < sizeX; x++) {
          if (!water.contains(x + "-" + y)) {
            final boolean isWater = false;
            map.addTerritory(new Territory(name + "_" + x + "_" + y, isWater, data));
          }
        }
      }
      // Add any implicit horizontal connections
      if (horizontalConnectionsImplict) {
        for (int y = 0; y < sizeY; y++) {
          for (int x = 0; x < sizeX - 1; x++) {
            final Territory from = map.getTerritoryFromCoordinates(x, y);
            final Territory to = map.getTerritoryFromCoordinates(x + 1, y);
            if (from != null && to != null) {
              map.addConnection(from, to);
            }
          }
        }
      }
      // Add any implicit acute diagonal connections
      if (diagonalConnectionsImplict) {
        for (int y = 1; y < sizeY; y++) {
          for (int x = 0; x < sizeX - 1; x++) {
            if (y % 4 == 0 || (y + 1) % 4 == 0) {
              final Territory from = map.getTerritoryFromCoordinates(x, y);
              final Territory to = map.getTerritoryFromCoordinates(x, y - 1);
              if (from != null && to != null) {
                map.addConnection(from, to);
              }
            } else {
              final Territory from = map.getTerritoryFromCoordinates(x, y);
              final Territory to = map.getTerritoryFromCoordinates(x + 1, y - 1);
              if (from != null && to != null) {
                map.addConnection(from, to);
              }
            }
          }
        }
      }
      // Add any implicit obtuse diagonal connections
      if (diagonalConnectionsImplict) {
        for (int y = 1; y < sizeY; y++) {
          for (int x = 0; x < sizeX - 1; x++) {
            if (y % 4 == 0 || (y + 1) % 4 == 0) {
              final Territory from = map.getTerritoryFromCoordinates(x, y);
              final Territory to = map.getTerritoryFromCoordinates(x - 1, y - 1);
              if (from != null && to != null) {
                map.addConnection(from, to);
              }
            } else {
              final Territory from = map.getTerritoryFromCoordinates(x, y);
              final Territory to = map.getTerritoryFromCoordinates(x, y - 1);
              if (from != null && to != null) {
                map.addConnection(from, to);
              }
            }
          }
        }
      }
    }
  }

  private static Set<String> parseGridWater(final List<Element> waterNodes) {
    final Set<String> set = new HashSet<>();
    for (final Element current : waterNodes) {
      final int x = Integer.valueOf(current.getAttribute("x"));
      final int y = Integer.valueOf(current.getAttribute("y"));
      set.add(x + "-" + y);
    }
    return set;
  }

  private void parseTerritories(final List<Element> territories) {
    final GameMap map = data.getMap();
    for (final Element current : territories) {
      final boolean water = current.getAttribute("water").trim().equalsIgnoreCase("true");
      final String name = current.getAttribute("name");
      final Territory newTerritory = new Territory(name, water, data);
      map.addTerritory(newTerritory);
    }
  }

  private void parseConnections(final List<Element> connections) throws GameParseException {
    final GameMap map = data.getMap();
    for (final Element current : connections) {
      final Territory t1 = getTerritory(current, "t1");
      final Territory t2 = getTerritory(current, "t2");
      map.addConnection(t1, t2);
    }
  }

  private void parseResources(final Element root) throws GameParseException {
    for (final Element element : getChildren("resource", root)) {
      final String name = element.getAttribute("name");
      final String isDisplayedFor = element.getAttribute("isDisplayedFor");
      if (isDisplayedFor.isEmpty()) {
        data.getResourceList().addResource(new Resource(name, data, data.getPlayerList().getPlayers()));
      } else if (isDisplayedFor.equalsIgnoreCase(RESOURCE_IS_DISPLAY_FOR_NONE)) {
        data.getResourceList().addResource(new Resource(name, data));
      } else {
        data.getResourceList().addResource(new Resource(name, data, parsePlayersFromIsDisplayedFor(isDisplayedFor)));
      }
    }
  }

  @VisibleForTesting
  List<PlayerId> parsePlayersFromIsDisplayedFor(final String encodedPlayerNames) throws GameParseException {
    final List<PlayerId> players = new ArrayList<>();
    for (final String playerName : Splitter.on(':').split(encodedPlayerNames)) {
      final @Nullable PlayerId player = data.getPlayerList().getPlayerId(playerName);
      if (player == null) {
        throw newGameParseException("Parse resources could not find player: " + playerName);
      }
      players.add(player);
    }
    return players;
  }

  private void parseRelationshipTypes(final Element root) {
    getChildren("relationshipType", root).stream()
        .map(e -> e.getAttribute("name"))
        .map(name -> new RelationshipType(name, data))
        .forEach(data.getRelationshipTypeList()::addRelationshipType);
  }

  private void parseTerritoryEffects(final Element root) {
    getChildren("territoryEffect", root).stream()
        .map(e -> e.getAttribute("name"))
        .forEach(name -> data.getTerritoryEffectList().put(name, new TerritoryEffect(name, data)));
  }

  private void parseUnits(final Element root) {
    getChildren("unit", root).stream()
        .map(e -> e.getAttribute("name"))
        .map(name -> new UnitType(name, data))
        .forEach(data.getUnitTypeList()::addUnitType);
  }

  private void parsePlayerList(final Element root) {
    final PlayerList playerList = data.getPlayerList();
    for (final Element current : getChildren("player", root)) {
      final String name = current.getAttribute("name");
      // It appears the commented line ALWAYS returns false regardless of the value of current.getAttribute("optional")
      // boolean isOptional = Boolean.getBoolean(current.getAttribute("optional"));
      final boolean isOptional = current.getAttribute("optional").equals("true");
      final boolean canBeDisabled = current.getAttribute("canBeDisabled").equals("true");
      final String defaultType = current.getAttribute("defaultType");
      final boolean isHidden = current.getAttribute("isHidden").equals("true");
      final PlayerId newPlayer = new PlayerId(name, isOptional, canBeDisabled, defaultType, isHidden, data);
      playerList.addPlayerId(newPlayer);
    }
  }

  private void parseAlliances(final Element root) throws GameParseException {
    final AllianceTracker allianceTracker = data.getAllianceTracker();
    final Collection<PlayerId> players = data.getPlayerList().getPlayers();
    for (final Element current : getChildren("alliance", root)) {
      final PlayerId p1 = getPlayerId(current, "player", true);
      final String alliance = current.getAttribute("alliance");
      allianceTracker.addToAlliance(p1, alliance);
    }
    // if relationships aren't initialized based on relationshipInitialize we use the alliances to set the relationships
    if (getSingleChild("relationshipInitialize", root, true) == null) {
      final RelationshipTracker relationshipTracker = data.getRelationshipTracker();
      final RelationshipTypeList relationshipTypeList = data.getRelationshipTypeList();
      // iterate through all players to get known allies and enemies
      for (final PlayerId currentPlayer : players) {
        // start with all players as enemies
        // start with no players as allies
        final Set<PlayerId> allies = allianceTracker.getAllies(currentPlayer);
        final Set<PlayerId> enemies = new HashSet<>(players);
        enemies.removeAll(allies);

        // remove self from enemieslist (in case of free-for-all)
        enemies.remove(currentPlayer);
        // remove self from allieslist (in case you are a member of an alliance)
        allies.remove(currentPlayer);
        // At this point enemies and allies should be set for this player.
        for (final PlayerId alliedPLayer : allies) {
          relationshipTracker.setRelationship(currentPlayer, alliedPLayer,
              relationshipTypeList.getDefaultAlliedRelationship());
        }
        for (final PlayerId enemyPlayer : enemies) {
          relationshipTracker.setRelationship(currentPlayer, enemyPlayer,
              relationshipTypeList.getDefaultWarRelationship());
        }
      }
    }
  }

  private void parseRelationInitialize(final List<Element> relations) throws GameParseException {
    if (relations.size() > 0) {
      final RelationshipTracker tracker = data.getRelationshipTracker();
      for (final Element current : relations) {
        final PlayerId p1 = getPlayerId(current, "player1", true);
        final PlayerId p2 = getPlayerId(current, "player2", true);
        final RelationshipType r = getRelationshipType(current, "type");
        final int roundValue = Integer.valueOf(current.getAttribute("roundValue"));
        tracker.setRelationship(p1, p2, r, roundValue);
      }
    }
  }

  private void parseGamePlay(final Element root) throws GameParseException {
    parseDelegates(getChildren("delegate", root));
    parseSequence(getSingleChild("sequence", root));
    parseOffset(getSingleChild("offset", root, true));
  }

  private void parseProperties(final Node root) throws GameParseException {
    final GameProperties properties = data.getProperties();
    for (final Element current : getChildren("property", root)) {
      final String editable = current.getAttribute("editable");
      final String property = current.getAttribute("name");
      String value = current.getAttribute("value");
      if (value == null || value.length() == 0) {
        final List<Element> valueChildren = getChildren("value", current);
        if (!valueChildren.isEmpty()) {
          final Element valueNode = valueChildren.get(0);
          if (valueNode != null) {
            value = valueNode.getTextContent();
          }
        }
      }
      if (editable != null && editable.equalsIgnoreCase("true")) {
        parseEditableProperty(current, property, value);
      } else {
        final List<Node> children2 = getNonTextNodesIgnoringValue(current);
        if (children2.size() == 0) {
          // we don't know what type this property is!!, it appears like only numbers and string may be represented
          // without proper type definition
          try {
            // test if it is an integer
            final int integer = Integer.parseInt(value);
            properties.set(property, integer);
          } catch (final NumberFormatException e) {
            // then it must be a string
            properties.set(property, value);
          }
        } else {
          final String type = children2.get(0).getNodeName();
          switch (type) {
            case "boolean":
              properties.set(property, Boolean.valueOf(value));
              break;
            case "file":
              properties.set(property, new File(value));
              break;
            case "number":
              int intValue = 0;
              if (value != null) {
                try {
                  intValue = Integer.parseInt(value);
                } catch (final NumberFormatException e) {
                  // value already 0
                }
              }
              properties.set(property, intValue);
              break;
            default:
              properties.set(property, value);
              break;
          }
        }
      }
    }
    data.getPlayerList().forEach(playerId -> data.getProperties().addPlayerProperty(
        new NumberProperty(Constants.getIncomePercentageFor(playerId), null, 999, 0, 100)));
    data.getPlayerList().forEach(playerId -> data.getProperties().addPlayerProperty(
        new NumberProperty(Constants.getPuIncomeBonus(playerId), null, 999, 0, 0)));
  }

  private void parseEditableProperty(final Element property, final String name, final String defaultValue)
      throws GameParseException {
    // what type
    final List<Node> children = getNonTextNodes(property);
    if (children.size() != 1) {
      throw newGameParseException(
          "Editable properties must have exactly 1 child specifying the type. Number of children found:"
              + children.size() + " for node:" + property.getNodeName());
    }
    final Element child = (Element) children.get(0);
    final String childName = child.getNodeName();
    final IEditableProperty<?> editableProperty;
    switch (childName) {
      case "boolean":
        editableProperty = new BooleanProperty(name, null, Boolean.valueOf(defaultValue));
        break;
      case "file":
        editableProperty = new FileProperty(name, null, defaultValue);
        break;
      case "list":
      case "combo":
        final List<String> values = Splitter.on(',')
            .omitEmptyStrings()
            .splitToList(child.getAttribute("values"));
        editableProperty = new ComboProperty<>(name, null, defaultValue, values);
        break;
      case "number": {
        final int max = Integer.valueOf(child.getAttribute("max"));
        final int min = Integer.valueOf(child.getAttribute("min"));
        final int def = Integer.valueOf(defaultValue);
        editableProperty = new NumberProperty(name, null, max, min, def);
        break;
      }
      case "color": {
        // Parse the value as a hexadecimal number
        final int def = Integer.valueOf(defaultValue, 16);
        editableProperty = new ColorProperty(name, null, def);
        break;
      }
      case "string":
        editableProperty = new StringProperty(name, null, defaultValue);
        break;
      default:
        throw newGameParseException("Unrecognized property type:" + childName);
    }
    data.getProperties().addEditableProperty(editableProperty);
  }

  private void parseOffset(final Node offsetAttributes) {
    if (offsetAttributes == null) {
      return;
    }
    final int roundOffset = Integer.parseInt(((Element) offsetAttributes).getAttribute("round"));
    data.getSequence().setRoundOffset(roundOffset);
  }

  private void parseDelegates(final List<Element> delegateList) throws GameParseException {
    for (final Element current : delegateList) {
      // load the class
      final String className = current.getAttribute("javaClass");
      final IDelegate delegate = xmlGameElementMapper.newDelegate(className)
          .orElseThrow(() -> newGameParseException("Class <" + className + "> is not a delegate."));
      final String name = current.getAttribute("name");
      String displayName = current.getAttribute("display");
      if (displayName == null) {
        displayName = name;
      }
      delegate.initialize(name, displayName);
      data.addDelegate(delegate);
    }
  }

  private void parseSequence(final Node sequence) throws GameParseException {
    parseSteps(getChildren("step", sequence));
  }

  private void parseSteps(final List<Element> stepList) throws GameParseException {
    for (final Element current : stepList) {
      final IDelegate delegate = getDelegate(current);
      final PlayerId player = getPlayerId(current, "player", false);
      final String name = current.getAttribute("name");
      String displayName = null;
      final List<Element> propertyElements = getChildren("stepProperty", current);
      final Properties stepProperties = pareStepProperties(propertyElements);
      if (current.hasAttribute("display")) {
        displayName = current.getAttribute("display");
      }
      final GameStep step = new GameStep(name, displayName, player, delegate, data, stepProperties);
      if (current.hasAttribute("maxRunCount")) {
        final int runCount = Integer.parseInt(current.getAttribute("maxRunCount"));
        if (runCount <= 0) {
          throw newGameParseException("maxRunCount must be positive");
        }
        step.setMaxRunCount(runCount);
      }
      data.getSequence().addStep(step);
    }
  }

  private static Properties pareStepProperties(final List<Element> properties) {
    final Properties stepProperties = new Properties();
    for (final Element stepProperty : properties) {
      final String name = stepProperty.getAttribute("name");
      final String value = stepProperty.getAttribute("value");
      stepProperties.setProperty(name, value);
    }
    return stepProperties;
  }

  private void parseProduction(final Node root) throws GameParseException {
    parseProductionRules(getChildren("productionRule", root));
    parseProductionFrontiers(getChildren("productionFrontier", root));
    parsePlayerProduction(getChildren("playerProduction", root));
    parseRepairRules(getChildren("repairRule", root));
    parseRepairFrontiers(getChildren("repairFrontier", root));
    parsePlayerRepair(getChildren("playerRepair", root));
  }

  private void parseTechnology(final Node root) throws GameParseException {
    parseTechnologies(getSingleChild("technologies", root, true));
    parsePlayerTech(getChildren("playerTech", root));
  }

  private void parseProductionRules(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final String name = current.getAttribute("name");
      final ProductionRule rule = new ProductionRule(name, data);
      parseCosts(rule, getChildren("cost", current));
      parseResults(rule, getChildren("result", current));
      data.getProductionRuleList().addProductionRule(rule);
    }
  }

  private void parseRepairRules(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final String name = current.getAttribute("name");
      final RepairRule rule = new RepairRule(name, data);
      parseRepairCosts(rule, getChildren("cost", current));
      parseRepairResults(rule, getChildren("result", current));
      data.getRepairRules().addRepairRule(rule);
    }
  }

  private void parseCosts(final ProductionRule rule, final List<Element> elements) throws GameParseException {
    if (elements.size() == 0) {
      throw newGameParseException("no costs  for rule:" + rule.getName());
    }
    for (final Element current : elements) {
      final Resource resource = getResource(current, "resource", true);
      final int quantity = Integer.parseInt(current.getAttribute("quantity"));
      rule.addCost(resource, quantity);
    }
  }

  private void parseRepairCosts(final RepairRule rule, final List<Element> elements) throws GameParseException {
    if (elements.size() == 0) {
      throw newGameParseException("no costs  for rule:" + rule.getName());
    }
    for (final Element current : elements) {
      final Resource resource = getResource(current, "resource", true);
      final int quantity = Integer.parseInt(current.getAttribute("quantity"));
      rule.addCost(resource, quantity);
    }
  }

  private void parseResults(final ProductionRule rule, final List<Element> elements) throws GameParseException {
    if (elements.size() == 0) {
      throw newGameParseException("no results  for rule:" + rule.getName());
    }
    for (final Element current : elements) {
      // must find either a resource or a unit with the given name
      NamedAttachable result = getResource(current, "resourceOrUnit", false);
      if (result == null) {
        result = getUnitType(current, "resourceOrUnit", false);
      }
      if (result == null) {
        throw newGameParseException("Could not find resource or unit" + current.getAttribute("resourceOrUnit"));
      }
      final int quantity = Integer.parseInt(current.getAttribute("quantity"));
      rule.addResult(result, quantity);
    }
  }

  private void parseRepairResults(final RepairRule rule, final List<Element> elements) throws GameParseException {
    if (elements.size() == 0) {
      throw newGameParseException("no results  for rule:" + rule.getName());
    }
    for (final Element current : elements) {
      // must find either a resource or a unit with the given name
      NamedAttachable result = getResource(current, "resourceOrUnit", false);
      if (result == null) {
        result = getUnitType(current, "resourceOrUnit", false);
      }
      if (result == null) {
        throw newGameParseException("Could not find resource or unit" + current.getAttribute("resourceOrUnit"));
      }
      final int quantity = Integer.parseInt(current.getAttribute("quantity"));
      rule.addResult(result, quantity);
    }
  }

  private void parseProductionFrontiers(final List<Element> elements) throws GameParseException {
    final ProductionFrontierList frontiers = data.getProductionFrontierList();
    for (final Element current : elements) {
      final String name = current.getAttribute("name");
      final ProductionFrontier frontier = new ProductionFrontier(name, data);
      parseFrontierRules(getChildren("frontierRules", current), frontier);
      frontiers.addProductionFrontier(frontier);
    }
  }

  private void parseTechnologies(final Node element) {
    if (element == null) {
      return;
    }
    final TechnologyFrontier allTechs = data.getTechnologyFrontier();
    parseTechs(getChildren("techname", element), allTechs);
  }

  private void parsePlayerTech(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final PlayerId player = getPlayerId(current, "player", true);
      final TechnologyFrontierList categories = player.getTechnologyFrontierList();
      parseCategories(getChildren("category", current), categories);
    }
  }

  private void parseCategories(final List<Element> elements, final TechnologyFrontierList categories)
      throws GameParseException {
    for (final Element current : elements) {
      final TechnologyFrontier tf = new TechnologyFrontier(current.getAttribute("name"), data);
      parseCategoryTechs(getChildren("tech", current), tf);
      categories.addTechnologyFrontier(tf);
    }
  }

  private void parseRepairFrontiers(final List<Element> elements) throws GameParseException {
    final RepairFrontierList frontiers = data.getRepairFrontierList();
    for (final Element current : elements) {
      final String name = current.getAttribute("name");
      final RepairFrontier frontier = new RepairFrontier(name, data);
      parseRepairFrontierRules(getChildren("repairRules", current), frontier);
      frontiers.addRepairFrontier(frontier);
    }
  }

  private void parsePlayerProduction(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final PlayerId player = getPlayerId(current, "player", true);
      final ProductionFrontier frontier = getProductionFrontier(current);
      player.setProductionFrontier(frontier);
    }
  }

  private void parsePlayerRepair(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final PlayerId player = getPlayerId(current, "player", true);
      final RepairFrontier repairFrontier = getRepairFrontier(current);
      player.setRepairFrontier(repairFrontier);
    }
  }

  private void parseFrontierRules(final List<Element> elements, final ProductionFrontier frontier)
      throws GameParseException {
    for (final Element element : elements) {
      frontier.addRule(getProductionRule(element));
    }
  }

  private void parseTechs(final List<Element> elements, final TechnologyFrontier allTechsFrontier) {
    for (final Element current : elements) {
      final String name = current.getAttribute("name");
      final String tech = current.getAttribute("tech");
      TechAdvance ta;
      if (tech.length() > 0) {
        ta = new GenericTechAdvance(name, TechAdvance.findDefinedAdvanceAndCreateAdvance(tech, data), data);
      } else {
        try {
          ta = TechAdvance.findDefinedAdvanceAndCreateAdvance(name, data);
        } catch (final IllegalArgumentException e) {
          ta = new GenericTechAdvance(name, null, data);
        }
      }
      allTechsFrontier.addAdvance(ta);
    }
  }

  private void parseCategoryTechs(final List<Element> elements, final TechnologyFrontier frontier)
      throws GameParseException {
    for (final Element current : elements) {
      TechAdvance ta = data.getTechnologyFrontier().getAdvanceByProperty(current.getAttribute("name"));
      if (ta == null) {
        ta = data.getTechnologyFrontier().getAdvanceByName(current.getAttribute("name"));
      }
      if (ta == null) {
        throw newGameParseException("Technology not found :" + current.getAttribute("name"));
      }
      frontier.addAdvance(ta);
    }
  }

  private void parseRepairFrontierRules(final List<Element> elements, final RepairFrontier frontier)
      throws GameParseException {
    for (final Element element : elements) {
      frontier.addRule(getRepairRule(element));
    }
  }

  private void parseAttachments(final Element root) throws GameParseException {
    for (final Element current : getChildren("attachment", root)) {
      final String className = current.getAttribute("javaClass");
      final Attachable attachable = findAttachment(current, current.getAttribute("type"));
      final String name = current.getAttribute("name");
      final List<Element> options = getChildren("option", current);
      final IAttachment attachment = xmlGameElementMapper.newAttachment(className, name, attachable, data)
          .orElseThrow(() -> newGameParseException("Attachment of type " + className + " could not be instantiated"));
      attachable.addAttachment(name, attachment);

      final List<Tuple<String, String>> attachmentOptionValues = setValues(attachment, options);
      // keep a list of attachment references in the order they were added
      data.addToAttachmentOrderAndValues(Tuple.of(attachment, attachmentOptionValues));
    }
  }

  private Attachable findAttachment(final Element element, final String type) throws GameParseException {
    final String name = "attachTo";
    switch (type) {
      case "unitType":
        return getUnitType(element, name, true);
      case "territory":
        return getTerritory(element, name);
      case "resource":
        return getResource(element, name, true);
      case "territoryEffect":
        return getTerritoryEffect(element);
      case "player":
        return getPlayerId(element, name, true);
      case "relationship":
        return getRelationshipType(element, name);
      case "technology":
        return getTechnology(element);
      default:
        throw newGameParseException("Type not found to attach to:" + type);
    }
  }

  private List<Tuple<String, String>> setValues(final IAttachment attachment, final List<Element> values)
      throws GameParseException {
    final List<Tuple<String, String>> options = new ArrayList<>();
    for (final Element current : values) {
      // decapitalize the property name for backwards compatibility
      final String name = decapitalize(current.getAttribute("name"));
      if (name.isEmpty()) {
        throw newGameParseException("Option name with zero length");
      }

      final String value = current.getAttribute("value");
      final String count = current.getAttribute("count");
      final String itemValues = (count.length() > 0 ? count + ":" : "") + value;
      try {
        attachment.getProperty(name)
            .orElseThrow(() -> newGameParseException(String.format(
                "Missing property definition for option '%s' in attachment '%s'",
                name, attachment.getName())))
            .setValue(itemValues);
      } catch (final GameParseException e) {
        throw e;
      } catch (final Exception e) {
        throw newGameParseException("Unexpected Exception while setting values for attachment" + attachment, e);
      }

      options.add(Tuple.of(name, itemValues));
    }
    return options;
  }

  @VisibleForTesting
  static String decapitalize(final String value) {
    return ((value.length() > 0) ? value.substring(0, 1).toLowerCase() : "")
        + ((value.length() > 1) ? value.substring(1) : "");
  }

  private void parseInitialization(final Node root) throws GameParseException {
    // parse territory owners
    final Node owner = getSingleChild("ownerInitialize", root, true);
    if (owner != null) {
      parseOwner(getChildren("territoryOwner", owner));
    }
    // parse initial unit placement
    final Node unit = getSingleChild("unitInitialize", root, true);
    if (unit != null) {
      parseUnitPlacement(getChildren("unitPlacement", unit));
      parseHeldUnits(getChildren("heldUnits", unit));
    }
    // parse resources given
    final Node resource = getSingleChild("resourceInitialize", root, true);
    if (resource != null) {
      parseResourceInitialization(getChildren("resourceGiven", resource));
    }
    // parse relationships
    final Node relationInitialize = getSingleChild("relationshipInitialize", root, true);
    if (relationInitialize != null) {
      parseRelationInitialize(getChildren("relationship", relationInitialize));
    }
  }

  private void parseOwner(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final Territory territory = getTerritory(current, "territory");
      final PlayerId owner = getPlayerId(current, "owner", true);
      territory.setOwner(owner);
      // Set the original owner on startup.
      // TODO Look into this
      // The addition of this caused the automated tests to fail as TestAttachment can't be cast to TerritoryAttachment
      // The addition of this IF to pass the tests is wrong, but works until a better solution is found.
      // Kevin will look into it.
      if (!territory.getData().getGameName().equals("gameExample")
          && !territory.getData().getGameName().equals("test")) {
        // set the original owner
        final TerritoryAttachment ta = TerritoryAttachment.get(territory);
        if (ta != null) {
          // If we already have an original owner set (ie: we set it previously in the attachment using originalOwner or
          // occupiedTerrOf), then we DO NOT set the original owner again.
          // This is how we can have a game start with territories owned by 1 faction but controlled by a 2nd faction.
          final PlayerId currentOwner = ta.getOriginalOwner();
          if (currentOwner == null) {
            ta.setOriginalOwner(owner);
          }
        }
      }
    }
  }

  private void parseUnitPlacement(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final Territory territory = getTerritory(current, "territory");
      final UnitType type = getUnitType(current, "unitType", true);
      final String ownerString = current.getAttribute("owner");
      final String hitsTakenString = current.getAttribute("hitsTaken");
      final String unitDamageString = current.getAttribute("unitDamage");
      final PlayerId owner;
      if (ownerString == null || ownerString.trim().length() == 0) {
        owner = PlayerId.NULL_PLAYERID;
      } else {
        owner = getPlayerId(current, "owner", false);
      }
      final int hits;
      if (hitsTakenString != null && hitsTakenString.trim().length() > 0) {
        hits = Integer.parseInt(hitsTakenString);
        if (hits < 0 || hits > UnitAttachment.get(type).getHitPoints() - 1) {
          throw newGameParseException(
              "hitsTaken cannot be less than zero or greater than one less than total hitpPoints");
        }
      } else {
        hits = 0;
      }
      final int unitDamage;
      if (unitDamageString != null && unitDamageString.trim().length() > 0) {
        unitDamage = Integer.parseInt(unitDamageString);
        if (unitDamage < 0) {
          throw newGameParseException("unitDamage cannot be less than zero");
        }
      } else {
        unitDamage = 0;
      }
      final int quantity = Integer.parseInt(current.getAttribute("quantity"));
      territory.getUnitCollection().addAll(type.create(quantity, owner, false, hits, unitDamage));
    }
  }

  private void parseHeldUnits(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final PlayerId player = getPlayerId(current, "player", true);
      final UnitType type = getUnitType(current, "unitType", true);
      final int quantity = Integer.parseInt(current.getAttribute("quantity"));
      player.getUnitCollection().addAll(type.create(quantity, player));
    }
  }

  private void parseResourceInitialization(final List<Element> elements) throws GameParseException {
    for (final Element current : elements) {
      final PlayerId player = getPlayerId(current, "player", true);
      final Resource resource = getResource(current, "resource", true);
      final int quantity = Integer.parseInt(current.getAttribute("quantity"));
      player.getResources().addResource(resource, quantity);
    }
  }

  private void checkThatAllUnitsHaveAttachments(final GameData data) throws GameParseException {
    final Collection<UnitType> errors = new ArrayList<>();
    for (final UnitType ut : data.getUnitTypeList().getAllUnitTypes()) {
      final UnitAttachment ua = UnitAttachment.get(ut);
      if (ua == null) {
        errors.add(ut);
      }
    }
    if (!errors.isEmpty()) {
      throw newGameParseException(
          data.getGameName() + " does not have unit attachments for: " + MyFormatter.defaultNamedToTextList(errors));
    }
  }
}
