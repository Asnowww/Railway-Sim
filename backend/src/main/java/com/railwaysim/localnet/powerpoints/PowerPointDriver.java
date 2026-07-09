package com.railwaysim.localnet.powerpoints;

import java.util.List;

public interface PowerPointDriver {

    List<PowerPointValue> snapshot(List<PowerPointDefinition> definitions);

    PowerPointValue write(PowerPointDefinition definition, String value);
}
