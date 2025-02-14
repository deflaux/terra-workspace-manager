package bio.terra.workspace.service.resource.model;

import bio.terra.workspace.generated.model.ApiCloningInstructionsEnum;
import com.google.common.collect.BiMap;
import com.google.common.collect.EnumBiMap;
import com.google.common.collect.EnumHashBiMap;
import javax.annotation.Nullable;

/**
 * Enum describing how to treat a resource when its containing workspace is cloned. The values
 * stored with the resources may be overridden at clone time.
 *
 * <p>COPY_NOTHING - do not clone anything for this resource
 *
 * <p>COPY_DEFINITION - copy the resource definition, but no data
 *
 * <p>COPY_RESOURCE - copy the resource, including its data
 *
 * <p>COPY_REFERENCE - create a referenced resource pointing to this resource, or to the resource it
 * references (for referenced resources)
 */
public enum CloningInstructions {
  COPY_NOTHING,
  COPY_DEFINITION,
  COPY_RESOURCE,
  COPY_REFERENCE;

  private static final BiMap<CloningInstructions, ApiCloningInstructionsEnum> instructionMap =
      EnumBiMap.create(CloningInstructions.class, ApiCloningInstructionsEnum.class);

  private static final BiMap<CloningInstructions, String> sqlMap =
      EnumHashBiMap.create(CloningInstructions.class);

  static {
    instructionMap.put(CloningInstructions.COPY_NOTHING, ApiCloningInstructionsEnum.NOTHING);
    instructionMap.put(CloningInstructions.COPY_DEFINITION, ApiCloningInstructionsEnum.DEFINITION);
    instructionMap.put(CloningInstructions.COPY_RESOURCE, ApiCloningInstructionsEnum.RESOURCE);
    instructionMap.put(CloningInstructions.COPY_REFERENCE, ApiCloningInstructionsEnum.REFERENCE);

    sqlMap.put(COPY_NOTHING, "COPY_NOTHING");
    sqlMap.put(COPY_DEFINITION, "COPY_DEFINITION");
    sqlMap.put(COPY_RESOURCE, "COPY_RESOURCE");
    sqlMap.put(COPY_REFERENCE, "COPY_REFERENCE");
  }

  @Nullable
  public static CloningInstructions fromApiModel(@Nullable ApiCloningInstructionsEnum modelEnum) {
    if (null == modelEnum) {
      return null;
    }
    return instructionMap.inverse().get(modelEnum);
  }

  public ApiCloningInstructionsEnum toApiModel() {
    return instructionMap.get(this);
  }

  /** Convert this to a String to be serialized to the DB. */
  public String toSql() {
    return sqlMap.get(this);
  }

  /** Deserialize a string from DB. */
  public static CloningInstructions fromSql(String s) {
    return sqlMap.inverse().get(s);
  }
}
