package games.strategy.engine.data.changefactory;

import java.util.ArrayList;
import java.util.Collection;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitCollection;
import games.strategy.engine.data.UnitHolder;

class RemoveUnits extends Change {
  private static final long serialVersionUID = -6410444472951010568L;

  private final String name;
  private final Collection<Unit> units;
  private final String type;

  RemoveUnits(final UnitCollection collection, final Collection<Unit> units) {
    this(collection.getHolder().getName(), collection.getHolder().getType(), units);
  }

  RemoveUnits(final String name, final String type, final Collection<Unit> units) {
    this.units = new ArrayList<>(units);
    this.name = name;
    this.type = type;
  }

  @Override
  public Change invert() {
    return new AddUnits(name, type, units);
  }

  @Override
  protected void perform(final GameData data) {
    final UnitHolder holder = data.getUnitHolder(name, type);
    if (!holder.getUnitCollection().containsAll(units)) {
      throw new IllegalStateException("Not all units present in:" + name + ".  Trying to remove:" + units
          + " present:" + holder.getUnits());
    }
    holder.getUnitCollection().removeAll(units);
  }

  @Override
  public String toString() {
    return "Remove unit change. Remove from:" + name + " units:" + units;
  }
}
