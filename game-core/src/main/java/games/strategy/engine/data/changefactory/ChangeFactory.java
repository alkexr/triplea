package games.strategy.engine.data.changefactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.triplea.java.collections.IntegerMap;

import games.strategy.engine.data.BombingUnitDamageChange;
import games.strategy.engine.data.Change;
import games.strategy.engine.data.ChangeAttachmentChange;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.IAttachment;
import games.strategy.engine.data.PlayerId;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.ResourceCollection;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitHitsChange;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.data.BattleRecords;

/**
 * All changes made to GameData should be made through changes produced here.
 *
 * <p>
 * The way to change game data is to
 * </p>
 *
 * <ol>
 * <li>Create a change with a ChangeFactory.change** or ChangeFactory.set** method</li>
 * <li>Execute that change through DelegateBridge.addChange()</li>
 * </ol>
 *
 * <p>
 * In this way changes to the game data can be co-ordinated across the network.
 * </p>
 */
public class ChangeFactory {
  public static final Change EMPTY_CHANGE = new Change() {
    private static final long serialVersionUID = -5514560889478876641L;

    @Override
    protected void perform(final GameData data) {}

    @Override
    public Change invert() {
      return this;
    }

    // when de-serializing, always return the singleton
    private Object readResolve() {
      return ChangeFactory.EMPTY_CHANGE;
    }

    @Override
    public boolean isEmpty() {
      return true;
    }
  };

  private ChangeFactory() {}

  public static Change changeOwner(final Territory territory, final PlayerId owner) {
    return new OwnerChange(territory, owner);
  }

  public static Change changeOwner(final Collection<Unit> units, final PlayerId owner, final Territory location) {
    return new PlayerOwnerChange(units, owner, location);
  }

  public static Change changeOwner(final Unit unit, final PlayerId owner, final Territory location) {
    return new PlayerOwnerChange(Collections.singleton(unit), owner, location);
  }

  public static Change addUnits(final Territory territory, final Collection<Unit> units) {
    return new AddUnits(territory.getUnitCollection(), units);
  }

  public static Change addUnits(final PlayerId player, final Collection<Unit> units) {
    return new AddUnits(player.getUnitCollection(), units);
  }

  public static Change removeUnits(final Territory territory, final Collection<Unit> units) {
    return new RemoveUnits(territory.getUnitCollection(), units);
  }

  public static Change removeUnits(final PlayerId player, final Collection<Unit> units) {
    return new RemoveUnits(player.getUnitCollection(), units);
  }

  public static Change moveUnits(final Territory start, final Territory end, final Collection<Unit> units) {
    return new CompositeChange(Arrays.asList(removeUnits(start, units), addUnits(end, units)));
  }

  public static Change changeProductionFrontier(final PlayerId player, final ProductionFrontier frontier) {
    return new ProductionFrontierChange(frontier, player);
  }

  public static Change changePlayerWhoAmIChange(final PlayerId player, final String encodedPlayerTypeAndName) {
    return new PlayerWhoAmIChange(encodedPlayerTypeAndName, player);
  }

  public static Change changeResourcesChange(final PlayerId player, final Resource resource, final int quantity) {
    return new ChangeResourceChange(player, resource, quantity);
  }

  public static Change removeResourceCollection(final PlayerId id, final ResourceCollection resourceCollection) {
    final CompositeChange compositeChange = new CompositeChange();
    for (final Resource r : resourceCollection.getResourcesCopy().keySet()) {
      compositeChange.add(new ChangeResourceChange(id, r, -resourceCollection.getQuantity(r)));
    }
    return compositeChange;
  }

  public static Change setProperty(final String property, final Object value, final GameData data) {
    return new SetPropertyChange(property, value, data.getProperties());
  }

  /**
   * Must already include existing damage to the unit. This does not add damage, it sets damage.
   */
  public static Change unitsHit(final IntegerMap<Unit> newHits) {
    return new UnitHitsChange(newHits);
  }

  /**
   * Must already include existing damage to the unit. This does not add damage, it sets damage.
   */
  public static Change bombingUnitDamage(final IntegerMap<Unit> newDamage) {
    return new BombingUnitDamageChange(newDamage);
  }

  public static Change addProductionRule(final ProductionRule rule, final ProductionFrontier frontier) {
    return new AddProductionRule(rule, frontier);
  }

  public static Change removeProductionRule(final ProductionRule rule, final ProductionFrontier frontier) {
    return new RemoveProductionRule(rule, frontier);
  }

  public static Change addAvailableTech(final TechnologyFrontier tf, final TechAdvance ta, final PlayerId player) {
    return new AddAvailableTech(tf, ta, player);
  }

  public static Change removeAvailableTech(final TechnologyFrontier tf, final TechAdvance ta, final PlayerId player) {
    return new RemoveAvailableTech(tf, ta, player);
  }

  public static Change attachmentPropertyChange(final IAttachment attachment, final Object newValue,
      final String property) {
    return new ChangeAttachmentChange(attachment, newValue, property);
  }

  /**
   * You don't want to clear the variable first unless you are setting some variable where the setting method is
   * actually adding things to a list rather than overwriting.
   */
  public static Change attachmentPropertyChange(final IAttachment attachment, final Object newValue,
      final String property, final boolean resetFirst) {
    return new ChangeAttachmentChange(attachment, newValue, property, resetFirst);
  }

  /**
   * You don't want to clear the variable first unless you are setting some variable where the setting method is
   * actually adding things to a list rather than overwriting.
   */
  public static Change attachmentPropertyReset(final IAttachment attachment, final String property) {
    return new AttachmentPropertyReset(attachment, property);
  }

  public static Change genericTechChange(final TechAttachment attachment, final boolean value, final String property) {
    return new GenericTechChange(attachment, value, property);
  }

  public static Change unitPropertyChange(final Unit unit, final Object newValue, final String propertyName) {
    return new ObjectPropertyChange(unit, propertyName, newValue);
  }

  public static Change addBattleRecords(final BattleRecords records, final GameData data) {
    return new AddBattleRecordsChange(records, data);
  }

  /**
   * Creates a change of relationshipType between 2 players, for example: change Germany-France relationship from
   * neutral to war.
   *
   * @return the Change of relationship between 2 players
   */
  public static Change relationshipChange(final PlayerId player, final PlayerId player2,
      final RelationshipType currentRelation, final RelationshipType newRelation) {
    return new RelationshipChange(player, player2, currentRelation, newRelation);
  }

  /**
   * Mark units as having no movement.
   *
   * @param units referring units
   * @return change that contains marking of units as having no movement
   */
  public static Change markNoMovementChange(final Collection<Unit> units) {
    final CompositeChange change = new CompositeChange();
    for (final Unit unit : units) {
      if (TripleAUnit.get(unit).getMovementLeft() > 0) {
        change.add(markNoMovementChange(unit));
      }
    }
    if (change.isEmpty()) {
      return EMPTY_CHANGE;
    }
    return change;
  }

  public static Change markNoMovementChange(final Unit unit) {
    return unitPropertyChange(unit, TripleAUnit.get(unit).getMaxMovementAllowed(), TripleAUnit.ALREADY_MOVED);
  }
}
