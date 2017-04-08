package games.strategy.triplea.delegate;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.IBattle.BattleType;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.player.ITripleAPlayer;
import games.strategy.triplea.ui.MovePanel;
import games.strategy.triplea.util.TransportUtils;
import games.strategy.util.CompositeMatch;
import games.strategy.util.CompositeMatchAnd;
import games.strategy.util.CompositeMatchOr;
import games.strategy.util.Match;
import games.strategy.util.Util;

public class MovePerformer implements Serializable {
  private static final long serialVersionUID = 3752242292777658310L;
  private transient AbstractMoveDelegate m_moveDelegate;
  private transient IDelegateBridge m_bridge;
  private transient PlayerID m_player;
  private AAInMoveUtil m_aaInMoveUtil;
  private final ExecutionStack m_executionStack = new ExecutionStack();
  private UndoableMove m_currentMove;
  private Map<Unit, Collection<Unit>> m_newDependents;
  private Collection<Unit> arrivingUnits;

  MovePerformer() {}

  private BattleTracker getBattleTracker() {
    return DelegateFinder.battleDelegate(m_bridge.getData()).getBattleTracker();
  }

  void initialize(final AbstractMoveDelegate delegate) {
    m_moveDelegate = delegate;
    m_bridge = delegate.getBridge();
    m_player = m_bridge.getPlayerID();
    if (m_aaInMoveUtil != null) {
      m_aaInMoveUtil.initialize(m_bridge);
    }
  }

  private ITripleAPlayer getRemotePlayer(final PlayerID id) {
    return (ITripleAPlayer) m_bridge.getRemotePlayer(id);
  }

  private ITripleAPlayer getRemotePlayer() {
    return getRemotePlayer(m_player);
  }

  void moveUnits(final Collection<Unit> units, final Route route, final PlayerID id,
      final Collection<Unit> transportsToLoad, final Map<Unit, Collection<Unit>> newDependents,
      final UndoableMove currentMove) {
    m_currentMove = currentMove;
    m_newDependents = newDependents;
    populateStack(units, route, id, transportsToLoad);
    m_executionStack.execute(m_bridge);
  }

  public void resume() {
    m_executionStack.execute(m_bridge);
  }

  /**
   * We assume that the move is valid.
   */
  private void populateStack(final Collection<Unit> units, final Route route, final PlayerID id,
      final Collection<Unit> transportsToLoad) {
    final IExecutable preAAFire = new IExecutable() {
      private static final long serialVersionUID = -7945930782650355037L;

      @Override
      public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
        // if we are moving out of a battle zone, mark it
        // this can happen for air units moving out of a battle zone
        for (final IBattle battle : getBattleTracker().getPendingBattles(route.getStart(), null)) {
          for (final Unit unit : units) {
            final Route routeUnitUsedToMove = m_moveDelegate.getRouteUsedToMoveInto(unit, route.getStart());
            if (battle != null) {
              battle.removeAttack(routeUnitUsedToMove, Collections.singleton(unit));
            }
          }
        }
      }
    };
    // hack to allow the executables to share state
    final IExecutable fireAA = new IExecutable() {
      private static final long serialVersionUID = -3780228078499895244L;

      @Override
      public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
        final Collection<Unit> aaCasualties = fireAA(route, units);
        final Set<Unit> aaCasualtiesWithDependents = new HashSet<>();
        // need to remove any dependents here
        if (aaCasualties != null) {
          aaCasualtiesWithDependents.addAll(aaCasualties);
          final Map<Unit, Collection<Unit>> dependencies = TransportTracker.transporting(units, units);
          for (final Unit u : aaCasualties) {
            final Collection<Unit> dependents = dependencies.get(u);
            if (dependents != null) {
              aaCasualtiesWithDependents.addAll(dependents);
            }
            // we might have new dependents too (ie: paratroopers)
            final Collection<Unit> newDependents = m_newDependents.get(u);
            if (newDependents != null) {
              aaCasualtiesWithDependents.addAll(newDependents);
            }
          }
        }
        arrivingUnits = Util.difference(units, aaCasualtiesWithDependents);
      }
    };
    final IExecutable postAAFire = new IExecutable() {
      private static final long serialVersionUID = 670783657414493643L;

      @Override
      public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
        // if any non enemy territories on route
        // or if any enemy units on route the
        // battles on (note water could have enemy but its
        // not owned)
        final GameData data = bridge.getData();
        final CompositeMatch<Territory> mustFightThrough = getMustFightThroughMatch(id, data);
        final Collection<Unit> arrived = Collections.unmodifiableList(Util.intersection(units, arrivingUnits));
        // Reset Optional
        arrivingUnits = new ArrayList<>();
        final Collection<Unit> arrivedCopyForBattles = new ArrayList<>(arrived);
        final Map<Unit, Unit> transporting = TransportUtils.mapTransports(route, arrived, transportsToLoad);
        // If we have paratrooper land units being carried by air units, they should be dropped off in the last
        // territory. This means they
        // are still dependent during the middle steps of the route.
        final Collection<Unit> dependentOnSomethingTilTheEndOfRoute = new ArrayList<>();
        final Collection<Unit> airTransports = Match.getMatches(arrived, Matches.UnitIsAirTransport);
        final Collection<Unit> paratroops = Match.getMatches(arrived, Matches.UnitIsAirTransportable);
        if (!airTransports.isEmpty() && !paratroops.isEmpty()) {
          final Map<Unit, Unit> transportingAir =
              TransportUtils.mapTransportsToLoad(paratroops, airTransports);
          dependentOnSomethingTilTheEndOfRoute.addAll(transportingAir.keySet());
        }
        final Collection<Unit> presentFromStartTilEnd = new ArrayList<>(arrived);
        presentFromStartTilEnd.removeAll(dependentOnSomethingTilTheEndOfRoute);
        final CompositeChange change = new CompositeChange();
        if (games.strategy.triplea.Properties.getUseFuelCost(data)) {
          // markFuelCostResourceChange must be done
          // before we load/unload units
          change.add(markFuelCostResourceChange(units, route, id, data));
        }
        markTransportsMovement(arrived, transporting, route);
        if (route.someMatch(mustFightThrough) && arrived.size() != 0) {
          boolean bombing = false;
          boolean ignoreBattle = false;
          // could it be a bombing raid
          final Collection<Unit> enemyUnits = route.getEnd().getUnits().getMatches(Matches.enemyUnit(id, data));
          final Collection<Unit> enemyTargetsTotal = Match.getMatches(enemyUnits,
              new CompositeMatchAnd<>(Matches.UnitIsAtMaxDamageOrNotCanBeDamaged(route.getEnd()).invert(),
                  Matches.unitIsBeingTransported().invert()));
          final CompositeMatchOr<Unit> allBombingRaid = new CompositeMatchOr<>(Matches.UnitIsStrategicBomber);
          final boolean canCreateAirBattle =
              !enemyTargetsTotal.isEmpty()
                  && games.strategy.triplea.Properties.getRaidsMayBePreceededByAirBattles(data)
                  && AirBattle.territoryCouldPossiblyHaveAirBattleDefenders(route.getEnd(), id, data, true);
          if (canCreateAirBattle) {
            allBombingRaid.add(Matches.unitCanEscort);
          }
          final boolean allCanBomb = Match.allMatch(arrived, allBombingRaid);
          final Collection<Unit> enemyTargets =
              Match.getMatches(enemyTargetsTotal,
                  Matches.unitIsOfTypes(UnitAttachment
                      .getAllowedBombingTargetsIntersection(Match.getMatches(arrived, Matches.UnitIsStrategicBomber),
                          data)));
          final boolean targetsOrEscort = !enemyTargets.isEmpty()
              || (!enemyTargetsTotal.isEmpty() && canCreateAirBattle && Match.allMatch(arrived, Matches.unitCanEscort));
          boolean targetedAttack = false;
          // if it's all bombers and there's something to bomb
          if (allCanBomb && targetsOrEscort && GameStepPropertiesHelper.isCombatMove(data)) {
            bombing = getRemotePlayer().shouldBomberBomb(route.getEnd());
            // if bombing and there's something to target- ask what to bomb
            if (bombing) {
              // CompositeMatchOr<Unit> unitsToBeBombed = new CompositeMatchOr<Unit>(Matches.UnitIsFactory,
              // Matches.UnitCanBeDamagedButIsNotFactory);
              // determine which unit to bomb
              Unit target;
              if (enemyTargets.size() > 1
                  && games.strategy.triplea.Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(data)
                  && !canCreateAirBattle) {
                target = getRemotePlayer().whatShouldBomberBomb(route.getEnd(), enemyTargets, arrived);
              } else if (!enemyTargets.isEmpty()) {
                target = enemyTargets.iterator().next();
              } else {
                // in case we are escorts only
                target = enemyTargetsTotal.iterator().next();
              }
              if (target == null) {
                bombing = false;
                targetedAttack = false;
              } else {
                targetedAttack = true;
                final HashMap<Unit, HashSet<Unit>> targets = new HashMap<>();
                targets.put(target, new HashSet<>(arrived));
                // createdBattle = true;
                getBattleTracker().addBattle(route, arrivedCopyForBattles, bombing, id, m_bridge, m_currentMove,
                    dependentOnSomethingTilTheEndOfRoute, targets, false);
              }
            }
          }
          // Ignore Trn on Trn forces.
          if (isIgnoreTransportInMovement(bridge.getData())) {
            final boolean allOwnedTransports = Match.allMatch(arrived, Matches.UnitIsTransportButNotCombatTransport);
            final boolean allEnemyTransports = Match.allMatch(enemyUnits, Matches.UnitIsTransportButNotCombatTransport);
            // If everybody is a transport, don't create a battle
            if (allOwnedTransports && allEnemyTransports) {
              ignoreBattle = true;
            }
          }
          if (!ignoreBattle && GameStepPropertiesHelper.isCombatMove(data) && !targetedAttack) {
            // createdBattle = true;
            if (bombing) {
              getBattleTracker().addBombingBattle(route, arrivedCopyForBattles, id, m_bridge, m_currentMove,
                  dependentOnSomethingTilTheEndOfRoute);
            } else {
              getBattleTracker().addBattle(route, arrivedCopyForBattles, id, m_bridge, m_currentMove,
                  dependentOnSomethingTilTheEndOfRoute);
            }
          }
          if (!ignoreBattle && GameStepPropertiesHelper.isNonCombatMove(data, false) && !targetedAttack) {
            // We are in non-combat move phase, and we are taking over friendly territories. No need for a battle. (This
            // could get really
            // difficult if we want these recorded in battle records).
            for (final Territory t : route
                .getMatches(new CompositeMatchAnd<>(
                    Matches
                        .territoryIsOwnedByPlayerWhosRelationshipTypeCanTakeOverOwnedTerritoryAndPassableAndNotWater(
                            id),
                    Matches.TerritoryIsBlitzable(id, data)))) {
              if (Matches.isTerritoryEnemy(id, data).match(t) || Matches.territoryHasEnemyUnits(id, data).match(t)) {
                continue;
              }
              if ((t.equals(route.getEnd()) && Match.allMatch(arrivedCopyForBattles, Matches.UnitIsAir))
                  || (!t.equals(route.getEnd()) && Match.allMatch(presentFromStartTilEnd, Matches.UnitIsAir))) {
                continue;
              }
              // createdBattle = true;
              getBattleTracker().takeOver(t, id, bridge, m_currentMove, arrivedCopyForBattles);
            }
          }
        }
        // mark movement
        final Change moveChange = markMovementChange(arrived, route, id);
        change.add(moveChange);
        // actually move the units
        if (route.getStart() != null && route.getEnd() != null) {
          // ChangeFactory.addUnits(route.getEnd(), arrived);
          Change remove = ChangeFactory.removeUnits(route.getStart(), units);
          Change add = ChangeFactory.addUnits(route.getEnd(), arrived);
          change.add(add, remove);
        }
        m_bridge.addChange(change);
        m_currentMove.addChange(change);
        m_currentMove.setDescription(MyFormatter.unitsToTextNoOwner(arrived) + " moved from "
            + route.getStart().getName() + " to " + route.getEnd().getName());
        m_moveDelegate.updateUndoableMoves(m_currentMove);
      }
    };
    m_executionStack.push(postAAFire);
    m_executionStack.push(fireAA);
    m_executionStack.push(preAAFire);
    m_executionStack.execute(m_bridge);
  }

  private static CompositeMatch<Territory> getMustFightThroughMatch(final PlayerID id, final GameData data) {
    final CompositeMatch<Territory> mustFightThrough = new CompositeMatchOr<>();
    mustFightThrough.add(Matches.isTerritoryEnemyAndNotUnownedWaterOrImpassableOrRestricted(id, data));
    mustFightThrough.add(Matches.territoryHasNonSubmergedEnemyUnits(id, data));
    mustFightThrough
        .add(Matches.territoryIsOwnedByPlayerWhosRelationshipTypeCanTakeOverOwnedTerritoryAndPassableAndNotWater(id));
    return mustFightThrough;
  }

  private Change markFuelCostResourceChange(final Collection<Unit> units, final Route route, final PlayerID id,
      final GameData data) {
    return ChangeFactory.removeResourceCollection(id,
        Route.getMovementFuelCostCharge(units, route, id, data /* , mustFight */));
  }

  private Change markMovementChange(final Collection<Unit> units, final Route route, final PlayerID id) {
    final GameData data = m_bridge.getData();
    final CompositeChange change = new CompositeChange();
    final Territory routeStart = route.getStart();
    final TerritoryAttachment taRouteStart = TerritoryAttachment.get(routeStart);
    final Territory routeEnd = route.getEnd();
    TerritoryAttachment taRouteEnd = null;
    if (routeEnd != null) {
      taRouteEnd = TerritoryAttachment.get(routeEnd);
    }
    // only units owned by us need to be marked
    final Iterator<Unit> iter = Match.getMatches(units, Matches.unitIsOwnedBy(id)).iterator();
    final RelationshipTracker relationshipTracker = data.getRelationshipTracker();
    while (iter.hasNext()) {
      final TripleAUnit unit = (TripleAUnit) iter.next();
      int moved = route.getMovementCost(unit);
      final UnitAttachment ua = UnitAttachment.get(unit.getType());
      if (ua.getIsAir()) {
        if (taRouteStart != null && taRouteStart.getAirBase()
            && relationshipTracker.isAllied(route.getStart().getOwner(), unit.getOwner())) {
          moved--;
        }
        if (taRouteEnd != null && taRouteEnd.getAirBase()
            && relationshipTracker.isAllied(route.getEnd().getOwner(), unit.getOwner())) {
          moved--;
        }
      }
      change.add(ChangeFactory.unitPropertyChange(unit, moved + unit.getAlreadyMoved(), TripleAUnit.ALREADY_MOVED));
    }
    // if neutrals were taken over mark land units with 0 movement
    // if entered a non blitzed conquered territory, mark with 0 movement
    if (GameStepPropertiesHelper.isCombatMove(data)
        && (MoveDelegate.getEmptyNeutral(route).size() != 0 || hasConqueredNonBlitzed(route))) {
      for (final Unit unit : Match.getMatches(units, Matches.UnitIsLand)) {
        change.add(ChangeFactory.markNoMovementChange(Collections.singleton(unit)));
      }
    }
    if (routeEnd != null && games.strategy.triplea.Properties.getSubsCanEndNonCombatMoveWithEnemies(data)
        && GameStepPropertiesHelper.isNonCombatMove(data, false) && routeEnd.getUnits()
            .someMatch(new CompositeMatchAnd<>(Matches.unitIsEnemyOf(data, id), Matches.UnitIsDestroyer))) {
      // if we are allowed to have our subs enter any sea zone with enemies during noncombat, we want to make sure we
      // can't keep moving them
      // if there is an enemy destroyer there
      for (final Unit unit : Match.getMatches(units,
          new CompositeMatchAnd<>(Matches.UnitIsSub, Matches.UnitIsAir.invert()))) {
        change.add(ChangeFactory.markNoMovementChange(Collections.singleton(unit)));
      }
    }
    return change;
  }

  /**
   * Marks transports and units involved in unloading with no movement left.
   */
  private void markTransportsMovement(final Collection<Unit> arrived, final Map<Unit, Unit> transporting,
      final Route route) {
    if (transporting == null) {
      return;
    }
    final GameData data = m_bridge.getData();
    final CompositeMatch<Unit> paratroopNAirTransports = new CompositeMatchOr<>();
    paratroopNAirTransports.add(Matches.UnitIsAirTransport);
    paratroopNAirTransports.add(Matches.UnitIsAirTransportable);
    final boolean paratroopsLanding = Match.someMatch(arrived, paratroopNAirTransports)
        && MoveValidator.allLandUnitsAreBeingParatroopered(arrived);
    final Map<Unit, Collection<Unit>> dependentAirTransportableUnits =
        MoveValidator.getDependents(Match.getMatches(arrived, Matches.UnitCanTransport));
    // add newly created dependents
    if (m_newDependents != null) {
      for (final Entry<Unit, Collection<Unit>> entry : m_newDependents.entrySet()) {
        Collection<Unit> dependents = dependentAirTransportableUnits.get(entry.getKey());
        if (dependents != null) {
          dependents.addAll(entry.getValue());
        } else {
          dependents = entry.getValue();
        }
        dependentAirTransportableUnits.put(entry.getKey(), dependents);
      }
    }
    // If paratroops moved normally (within their normal movement) remove their dependency to the airTransports
    // So they can all continue to move normally
    if (!paratroopsLanding && !dependentAirTransportableUnits.isEmpty()) {
      final Collection<Unit> airTransports = Match.getMatches(arrived, Matches.UnitIsAirTransport);
      airTransports.addAll(dependentAirTransportableUnits.keySet());
      MovePanel.clearDependents(airTransports);
    }
    // load the transports
    if (route.isLoad() || paratroopsLanding) {
      // mark transports as having transported
      for (Unit load : transporting.keySet()) {
        final Unit transport = transporting.get(load);
        if (!TransportTracker.transporting(transport).contains(load)) {
          final Change change = TransportTracker.loadTransportChange((TripleAUnit) transport, load);
          m_currentMove.addChange(change);
          m_currentMove.load(transport);
          m_bridge.addChange(change);
        }
      }
      if (transporting.isEmpty()) {
        for (final Unit airTransport : dependentAirTransportableUnits.keySet()) {
          for (final Unit unit : dependentAirTransportableUnits.get(airTransport)) {
            final Change change = TransportTracker.loadTransportChange((TripleAUnit) airTransport, unit);
            m_currentMove.addChange(change);
            m_currentMove.load(airTransport);
            m_bridge.addChange(change);
          }
        }
      }
    }
    if (route.isUnload() || paratroopsLanding) {
      final Set<Unit> units = new HashSet<>();
      units.addAll(transporting.values());
      units.addAll(transporting.keySet());
      // if there are multiple units on a single transport, the transport will be in units list multiple times
      if (transporting.isEmpty()) {
        units.addAll(dependentAirTransportableUnits.keySet());
        for (final Unit airTransport : dependentAirTransportableUnits.keySet()) {
          units.addAll(dependentAirTransportableUnits.get(airTransport));
        }
      }
      // any pending battles in the unloading zone?
      final BattleTracker tracker = getBattleTracker();
      final boolean pendingBattles = tracker.getPendingBattle(route.getStart(), false, BattleType.NORMAL) != null;
      for (Unit unit : units) {
        if (Matches.UnitIsAir.match(unit)) {
          continue;
        }
        final Unit transportedBy = ((TripleAUnit) unit).getTransportedBy();
        // we will unload our paratroopers after they land in battle (after aa guns fire)
        if (paratroopsLanding && transportedBy != null && Matches.UnitIsAirTransport.match(transportedBy)
            && GameStepPropertiesHelper.isCombatMove(data)
            && Matches.territoryHasNonSubmergedEnemyUnits(m_player, data).match(route.getEnd())) {
          continue;
        }
        // unload the transports
        final Change change1 = TransportTracker.unloadTransportChange((TripleAUnit) unit,
            m_currentMove.getRoute().getEnd(), m_player, pendingBattles);
        m_currentMove.addChange(change1);
        m_currentMove.unload(unit);
        m_bridge.addChange(change1);
        // set noMovement
        final Change change2 = ChangeFactory.markNoMovementChange(Collections.singleton(unit));
        m_currentMove.addChange(change2);
        m_bridge.addChange(change2);
      }
    }
  }

  private boolean hasConqueredNonBlitzed(final Route route) {
    final BattleTracker tracker = getBattleTracker();
    for (final Territory current : route.getSteps()) {
      if (tracker.wasConquered(current) && !tracker.wasBlitzed(current)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Fire aa guns. Returns units to remove.
   */
  private Collection<Unit> fireAA(final Route route, final Collection<Unit> units) {
    if (m_aaInMoveUtil == null) {
      m_aaInMoveUtil = new AAInMoveUtil();
    }
    m_aaInMoveUtil.initialize(m_bridge);
    final Collection<Unit> rVal =
        m_aaInMoveUtil.fireAA(route, units, UnitComparator.getLowestToHighestMovementComparator(), m_currentMove);
    m_aaInMoveUtil = null;
    return rVal;
  }

  private static boolean isIgnoreTransportInMovement(final GameData data) {
    return games.strategy.triplea.Properties.getIgnoreTransportInMovement(data);
  }
}
