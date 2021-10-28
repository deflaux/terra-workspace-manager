package bio.terra.workspace.service.resource.controlled;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ControlledAzureNetworkAttributes {
    private final String networkName;
    private final String region;

    @JsonCreator
    public ControlledAzureNetworkAttributes(
            @JsonProperty("networkName") String networkName, @JsonProperty("region") String region) {
        this.networkName = networkName;
        this.region = region;
    }

    public String getNetworkName() {
        return networkName;
    }

    public String getRegion() {
        return region;
    }
}
