package bio.terra.workspace.service.workspace.flight;

import static bio.terra.workspace.service.workspace.flight.WorkspaceFlightMapKeys.GOOGLE_PROJECT_ID;

import bio.terra.cloudres.google.cloudresourcemanager.CloudResourceManagerCow;
import bio.terra.stairway.FlightContext;
import bio.terra.stairway.FlightMap;
import bio.terra.stairway.Step;
import bio.terra.stairway.StepResult;
import bio.terra.stairway.exception.RetryException;
import bio.terra.workspace.service.iam.model.IamRole;
import bio.terra.workspace.service.workspace.CloudSyncRoleMapping;
import bio.terra.workspace.service.workspace.exceptions.RetryableCrlException;
import com.google.api.services.cloudresourcemanager.model.Binding;
import com.google.api.services.cloudresourcemanager.model.GetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.Policy;
import com.google.api.services.cloudresourcemanager.model.SetIamPolicyRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Step} that grants GCP IAM permissions to Sam policy groups.
 *
 * <p>This step will grant GCP IAM roles to the google groups underlying Sam policies. It follows a
 * read-modify-write pattern using GCP's field eTag to ensure the write does not clobber other
 * changes. The read-modify-write pattern is necessary to support existing IAM groups which the
 * Buffer Service may grant on projects before handing them out.
 *
 * <p>The "modify" part of this step specifically adds GCP bindings as specified in {@link
 * CloudSyncRoleMapping}. Note that the bindings list sent to GCP may contain multiple entries with
 * the same role. This is valid, though GCP will condense them into one binding per role internally.
 */
public class GoogleCloudSyncStep implements Step {

  private final CloudResourceManagerCow resourceManagerCow;

  private final Logger logger = LoggerFactory.getLogger(GoogleCloudSyncStep.class);

  public GoogleCloudSyncStep(CloudResourceManagerCow resourceManagerCow) {
    this.resourceManagerCow = resourceManagerCow;
  }

  @Override
  public StepResult doStep(FlightContext flightContext)
      throws InterruptedException, RetryException {
    String gcpProjectName = flightContext.getWorkingMap().get(GOOGLE_PROJECT_ID, String.class);
    FlightMap workingMap = flightContext.getWorkingMap();
    try {
      Policy currentPolicy =
          resourceManagerCow
              .projects()
              .getIamPolicy(gcpProjectName, new GetIamPolicyRequest())
              .execute();
      List<Binding> existingBindings = currentPolicy.getBindings();

      // GCP IAM always prefixes groups with the literal "group:"
      String readerGroup =
          "group:" + workingMap.get(WorkspaceFlightMapKeys.IAM_READER_GROUP_EMAIL, String.class);
      String writerGroup =
          "group:" + workingMap.get(WorkspaceFlightMapKeys.IAM_WRITER_GROUP_EMAIL, String.class);
      String ownerGroup =
          "group:" + workingMap.get(WorkspaceFlightMapKeys.IAM_OWNER_GROUP_EMAIL, String.class);
      List<Binding> newBindings = new ArrayList<>();
      newBindings.addAll(bindingsForRole(IamRole.READER, readerGroup));
      newBindings.addAll(bindingsForRole(IamRole.WRITER, writerGroup));
      newBindings.addAll(bindingsForRole(IamRole.OWNER, ownerGroup));

      // Add all existing bindings to ensure we don't accidentally clobber existing permissions.
      newBindings.addAll(existingBindings);
      Policy newPolicy =
          new Policy()
              .setVersion(currentPolicy.getVersion())
              .setBindings(newBindings)
              .setEtag(currentPolicy.getEtag());
      SetIamPolicyRequest iamPolicyRequest = new SetIamPolicyRequest().setPolicy(newPolicy);
      logger.info("Setting new Cloud Context IAM policy: " + iamPolicyRequest.toPrettyString());
      resourceManagerCow.projects().setIamPolicy(gcpProjectName, iamPolicyRequest).execute();
    } catch (IOException e) {
      throw new RetryableCrlException("Error setting IAM permissions", e);
    }
    return StepResult.getStepResultSuccess();
  }

  /**
   * Build a list of role bindings for a given group, using CloudSyncRoleMapping.
   *
   * @param role The role granted to this user. Translated to GCP roles using CloudSyncRoleMapping.
   * @param group The group being granted a role. Should be prefixed with the literal "group:" for
   *     GCP.
   */
  private List<Binding> bindingsForRole(IamRole role, String group) {
    List<Binding> bindings = new ArrayList<>();
    for (String gcpRole : CloudSyncRoleMapping.cloudSyncRoleMap.get(role)) {
      bindings.add(new Binding().setRole(gcpRole).setMembers(Collections.singletonList(group)));
    }
    return bindings;
  }

  /**
   * Because the project will be deleted when other steps are undone, we don't need to undo
   * permissions.
   */
  @Override
  public StepResult undoStep(FlightContext flightContext) throws InterruptedException {
    return StepResult.getStepResultSuccess();
  }
}