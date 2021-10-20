package bio.terra.workspace.service.resource.controlled;

import bio.terra.common.exception.InconsistentFieldsException;
import bio.terra.common.exception.MissingRequiredFieldException;
import bio.terra.workspace.db.DbSerDes;
import bio.terra.workspace.db.model.DbResource;
import bio.terra.workspace.generated.model.ApiAzureIpAttributes;
import bio.terra.workspace.generated.model.ApiAzureIpResource;
import bio.terra.workspace.service.resource.ValidationUtils;
import bio.terra.workspace.service.resource.WsmResourceType;
import bio.terra.workspace.service.resource.model.CloningInstructions;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class ControlledAzureNetworkResource extends ControlledResource {
    private final String networkName;
    private final String region;

    @JsonCreator
    public ControlledAzureNetworkResource(
            @JsonProperty("workspaceId") UUID workspaceId,
            @JsonProperty("resourceId") UUID resourceId,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("cloningInstructions") CloningInstructions cloningInstructions,
            @JsonProperty("assignedUser") String assignedUser,
            @JsonProperty("accessScope") AccessScopeType accessScope,
            @JsonProperty("managedBy") ManagedByType managedBy,
            @JsonProperty("networkName") String networkName,
            @JsonProperty("region") String region) {

        super(
                workspaceId,
                resourceId,
                name,
                description,
                cloningInstructions,
                assignedUser,
                accessScope,
                managedBy);
        this.networkName = networkName;
        this.region = region;
        validate();
    }

    public ControlledAzureNetworkResource(DbResource dbResource) {
        super(dbResource);
        ControlledAzureNetworkAttributes attributes =
                DbSerDes.fromJson(dbResource.getAttributes(), ControlledAzureNetworkAttributes.class);
        this.networkName = attributes.getNetworkName();
        this.region = attributes.getRegion();
        validate();
    }

    public String getNetworkName() {
        returnNetworkName;
    }

    public String getRegion() {
        return region;
    }

    public ApiAzureNetworkAttributes toApiAttributes() {
        return new ApiAzureNetworkAttributes().NetworkName(getNetworkName()).region(region.toString());
    }

    public ApiAzureNetworkResource toApiResource() {
        return new ApiAzureNetworkResource().metadata(super.toApiMetadata()).attributes(toApiAttributes());
    }

    @Override
    public WsmResourceType getResourceType() {
        return WsmResourceType.AZURE_NETWORK;
    }

    @Override
    public String attributesToJson() {
        return DbSerDes.toJson(new ControlledAzureIpAttributes(getIpName(), getRegion()));
    }

    @Override
    public void validate() {
        super.validate();
        if (getResourceType() != WsmResourceType.AZURE_IP) {
            throw new InconsistentFieldsException("Expected AZURE_IP");
        }
        if (getIpName() == null) {
            throw new MissingRequiredFieldException(
                    "Missing required ipName field for ControlledAzureIP.");
        }
        if (getRegion() == null) {
            throw new MissingRequiredFieldException(
                    "Missing required region field for ControlledAzureIP.");
        }
        ValidationUtils.validateIpName(getIpName());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        ControlledAzureIpResource that = (ControlledAzureIpResource) o;

        return ipName.equals(that.getIpName());
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + ipName.hashCode();
        return result;
    }

    public static ControlledAzureIpResource.Builder builder() {
        return new ControlledAzureIpResource.Builder();
    }

    public static class Builder {
        private UUID workspaceId;
        private UUID resourceId;
        private String name;
        private String description;
        private CloningInstructions cloningInstructions;
        private String assignedUser;
        private AccessScopeType accessScope;
        private ManagedByType managedBy;
        private String ipName;
        private String region;

        public ControlledAzureIpResource.Builder workspaceId(UUID workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public ControlledAzureIpResource.Builder resourceId(UUID resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public ControlledAzureIpResource.Builder name(String name) {
            this.name = name;
            return this;
        }

        public ControlledAzureIpResource.Builder description(String description) {
            this.description = description;
            return this;
        }

        public ControlledAzureIpResource.Builder cloningInstructions(
                CloningInstructions cloningInstructions) {
            this.cloningInstructions = cloningInstructions;
            return this;
        }

        public ControlledAzureIpResource.Builder ipName(String ipName) {
            this.ipName = ipName;
            return this;
        }

        public ControlledAzureIpResource.Builder region(String region) {
            this.region = region;
            return this;
        }

        public ControlledAzureIpResource.Builder assignedUser(String assignedUser) {
            this.assignedUser = assignedUser;
            return this;
        }

        public ControlledAzureIpResource.Builder accessScope(AccessScopeType accessScope) {
            this.accessScope = accessScope;
            return this;
        }

        public ControlledAzureIpResource.Builder managedBy(ManagedByType managedBy) {
            this.managedBy = managedBy;
            return this;
        }

        public ControlledAzureIpResource build() {
            return new ControlledAzureIpResource(
                    workspaceId,
                    resourceId,
                    name,
                    description,
                    cloningInstructions,
                    assignedUser,
                    accessScope,
                    managedBy,
                    ipName,
                    region);
        }
    }
}
