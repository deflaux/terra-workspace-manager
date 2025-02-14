package bio.terra.workspace.service.resource.controlled.cloud.azure.vm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public class ControlledAzureVmAttributes {
  private final String vmName;
  private final String region;
  private final String vmSize;
  private final String vmImage;

  private final UUID ipId;
  private final UUID networkId;
  private final UUID diskId;

  @JsonCreator
  public ControlledAzureVmAttributes(
      @JsonProperty("vmName") String vmName,
      @JsonProperty("region") String region,
      @JsonProperty("vmSize") String vmSize,
      @JsonProperty("vmImage") String vmImage,
      @JsonProperty("ipId") UUID ipId,
      @JsonProperty("networkId") UUID networkId,
      @JsonProperty("diskId") UUID diskId) {
    this.vmName = vmName;
    this.region = region;
    this.vmSize = vmSize;
    this.vmImage = vmImage;

    this.ipId = ipId;
    this.networkId = networkId;
    this.diskId = diskId;
  }

  public String getVmName() {
    return vmName;
  }

  public String getRegion() {
    return region;
  }

  public String getVmSize() {
    return vmSize;
  }

  public String getVmImage() {
    return vmImage;
  }

  public UUID getIpId() {
    return ipId;
  }

  public UUID getNetworkId() {
    return networkId;
  }

  public UUID getDiskId() {
    return diskId;
  }
}
