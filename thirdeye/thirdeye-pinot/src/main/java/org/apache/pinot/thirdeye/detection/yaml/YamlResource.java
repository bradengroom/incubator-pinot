/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pinot.thirdeye.detection.yaml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.Singleton;
import io.dropwizard.auth.Auth;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.annotation.security.PermitAll;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.pinot.thirdeye.anomaly.ThirdEyeAnomalyConfiguration;
import org.apache.pinot.thirdeye.anomaly.task.TaskConstants;
import org.apache.pinot.thirdeye.anomaly.task.TaskContext;
import org.apache.pinot.thirdeye.anomaly.task.TaskRunner;
import org.apache.pinot.thirdeye.api.Constants;
import org.apache.pinot.thirdeye.auth.ThirdEyePrincipal;
import org.apache.pinot.thirdeye.dashboard.DetectionPreviewConfiguration;
import org.apache.pinot.thirdeye.dataframe.util.MetricSlice;
import org.apache.pinot.thirdeye.datalayer.bao.DatasetConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.DetectionAlertConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.DetectionConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.EvaluationManager;
import org.apache.pinot.thirdeye.datalayer.bao.EventManager;
import org.apache.pinot.thirdeye.datalayer.bao.MergedAnomalyResultManager;
import org.apache.pinot.thirdeye.datalayer.bao.MetricConfigManager;
import org.apache.pinot.thirdeye.datalayer.bao.SessionManager;
import org.apache.pinot.thirdeye.datalayer.bao.TaskManager;
import org.apache.pinot.thirdeye.datalayer.dto.DetectionAlertConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.DetectionConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.SessionDTO;
import org.apache.pinot.thirdeye.datalayer.dto.TaskDTO;
import org.apache.pinot.thirdeye.datalayer.util.Predicate;
import org.apache.pinot.thirdeye.datasource.DAORegistry;
import org.apache.pinot.thirdeye.datasource.ThirdEyeCacheRegistry;
import org.apache.pinot.thirdeye.datasource.loader.AggregationLoader;
import org.apache.pinot.thirdeye.datasource.loader.DefaultAggregationLoader;
import org.apache.pinot.thirdeye.datasource.loader.DefaultTimeSeriesLoader;
import org.apache.pinot.thirdeye.datasource.loader.TimeSeriesLoader;
import org.apache.pinot.thirdeye.detection.ConfigUtils;
import org.apache.pinot.thirdeye.detection.DataProvider;
import org.apache.pinot.thirdeye.detection.DefaultDataProvider;
import org.apache.pinot.thirdeye.detection.DetectionPipeline;
import org.apache.pinot.thirdeye.detection.DetectionPipelineLoader;
import org.apache.pinot.thirdeye.detection.DetectionPipelineResult;
import org.apache.pinot.thirdeye.detection.TaskUtils;
import org.apache.pinot.thirdeye.detection.alert.DetectionAlertTaskInfo;
import org.apache.pinot.thirdeye.detection.alert.DetectionAlertTaskRunner;
import org.apache.pinot.thirdeye.detection.cache.builder.AnomaliesCacheBuilder;
import org.apache.pinot.thirdeye.detection.cache.builder.TimeSeriesCacheBuilder;
import org.apache.pinot.thirdeye.detection.onboard.YamlOnboardingTaskInfo;
import org.apache.pinot.thirdeye.detection.spi.components.BaselineProvider;
import org.apache.pinot.thirdeye.detection.spi.model.TimeSeries;
import org.apache.pinot.thirdeye.detection.validators.ConfigValidationException;
import org.apache.pinot.thirdeye.detection.validators.ConfigValidationUtils;
import org.apache.pinot.thirdeye.detection.validators.DetectionConfigValidator;
import org.apache.pinot.thirdeye.detection.validators.SubscriptionConfigValidator;
import org.apache.pinot.thirdeye.detection.yaml.translator.DetectionConfigTranslator;
import org.apache.pinot.thirdeye.detection.yaml.translator.SubscriptionConfigTranslator;
import org.apache.pinot.thirdeye.formatter.DetectionConfigFormatter;
import org.apache.pinot.thirdeye.rootcause.impl.MetricEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import static org.apache.pinot.thirdeye.dataframe.util.DataFrameUtils.*;
import static org.apache.pinot.thirdeye.detection.yaml.translator.SubscriptionConfigTranslator.*;


@Api(tags = {Constants.YAML_TAG})
@Singleton
public class YamlResource {
  protected static final Logger LOG = LoggerFactory.getLogger(YamlResource.class);
  private static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private enum YamlOperations {
    CREATING, UPDATING, PREVIEW, RUNNING
  }

  private static final String PROP_DETECTION = "detection";
  private static final String PROP_SUBSCRIPTION = "subscription";
  private static final String PROP_ALERT = PROP_DETECTION + "/" + PROP_SUBSCRIPTION;

  protected static final String PROP_DETECTION_ID = "detectionConfigId";
  protected static final String PROP_SUBSCRIPTION_ID = "subscriptionConfigId";

  private static final String PROP_SUBS_GROUP_NAME = "subscriptionGroupName";
  private static final String PROP_DETECTION_NAME = "detectionName";

  private static final String PROP_SESSION_KEY = "sessionKey";
  private static final String PROP_PRINCIPAL_TYPE = "principalType";
  private static final String PROP_SERVICE = "SERVICE";

  private static final String EMPTY_PAYLOAD_MSG = "The Yaml Payload in the request is empty.";
  private static final String DUPLICATE_KEY_MSG = "%s is already taken. Please use a different one.";
  private static final String MISSING_YAML_MSG = "%s yaml is missing.";

  // default onboarding replay period
  private static final long ONBOARDING_REPLAY_LOOKBACK = TimeUnit.DAYS.toMillis(30);

  private final DetectionConfigManager detectionConfigDAO;
  private final DetectionAlertConfigManager subscriptionConfigDAO;
  private final DetectionConfigValidator detectionValidator;
  private final SubscriptionConfigValidator subscriptionValidator;
  private final DataProvider provider;
  private final MetricConfigManager metricDAO;
  private final DatasetConfigManager datasetDAO;
  private final EventManager eventDAO;
  private final MergedAnomalyResultManager anomalyDAO;
  private final EvaluationManager evaluationDAO;
  private final TaskManager taskDAO;
  private final SessionManager sessionDAO;
  private final DetectionPipelineLoader loader;
  private final Yaml yaml;
  private final ExecutorService executor;
  private final long previewTimeout;
  private final DetectionConfigFormatter detectionConfigFormatter;
  private final RateLimiter onboardingRateLimiter;
  private final Map<String, Map<String, Object>> alerterConfig;

  public YamlResource(Map<String, Map<String, Object>> alerterConfig, DetectionPreviewConfiguration previewConfig,
      double alertOnboardingPermitPerSecond) {
    this.detectionConfigDAO = DAORegistry.getInstance().getDetectionConfigManager();
    this.subscriptionConfigDAO = DAORegistry.getInstance().getDetectionAlertConfigManager();
    this.metricDAO = DAORegistry.getInstance().getMetricConfigDAO();
    this.datasetDAO = DAORegistry.getInstance().getDatasetConfigDAO();
    this.eventDAO = DAORegistry.getInstance().getEventDAO();
    this.anomalyDAO = DAORegistry.getInstance().getMergedAnomalyResultDAO();
    this.taskDAO = DAORegistry.getInstance().getTaskDAO();
    this.evaluationDAO = DAORegistry.getInstance().getEvaluationManager();
    this.sessionDAO = DAORegistry.getInstance().getSessionDAO();
    this.yaml = new Yaml();
    this.executor = Executors.newFixedThreadPool(previewConfig.getParallelism());
    this.previewTimeout = previewConfig.getTimeout();
    this.alerterConfig = alerterConfig;
    this.onboardingRateLimiter = RateLimiter.create(alertOnboardingPermitPerSecond);

    TimeSeriesLoader timeseriesLoader =
        new DefaultTimeSeriesLoader(metricDAO, datasetDAO, ThirdEyeCacheRegistry.getInstance().getQueryCache(), ThirdEyeCacheRegistry.getInstance().getTimeSeriesCache());

    AggregationLoader aggregationLoader =
        new DefaultAggregationLoader(metricDAO, datasetDAO, ThirdEyeCacheRegistry.getInstance().getQueryCache(),
            ThirdEyeCacheRegistry.getInstance().getDatasetMaxDataTimeCache());

    this.loader = new DetectionPipelineLoader();

    this.provider = new DefaultDataProvider(metricDAO, datasetDAO, eventDAO, anomalyDAO, evaluationDAO,
        timeseriesLoader, aggregationLoader, loader, TimeSeriesCacheBuilder.getInstance(),
        AnomaliesCacheBuilder.getInstance());

    this.detectionValidator = new DetectionConfigValidator(this.provider);
    this.subscriptionValidator = new SubscriptionConfigValidator();
    this.detectionConfigFormatter = new DetectionConfigFormatter(metricDAO, datasetDAO);
  }

  public YamlResource(Map<String, Map<String, Object>> alerterConfig, DetectionPreviewConfiguration previewConfig) {
    this(alerterConfig, previewConfig, Double.MAX_VALUE);
  }

  /*
   * Helper method to build the detection config from a yaml.
   */
  private DetectionConfigDTO buildDetectionConfigFromYaml(long tuningStartTime, long tuningEndTime,
      @NotNull String yamlConfig, DetectionConfigDTO existingConfig) throws ConfigValidationException {
    // Configure the tuning window
    if (tuningStartTime == 0L && tuningEndTime == 0L) {
      // default tuning window 28 days
      tuningEndTime = System.currentTimeMillis();
      tuningStartTime = tuningEndTime - TimeUnit.DAYS.toMillis(28);
    }

    // Translate the raw yaml config to detection config object
    DetectionConfigDTO config = new DetectionConfigTranslator(yamlConfig, this.provider).translate();

    if (existingConfig != null) {
      config.setId(existingConfig.getId());
      config.setLastTimestamp(existingConfig.getLastTimestamp());
      config.setCreatedBy(existingConfig.getCreatedBy());
    }

    // Tune the detection config - Passes the raw yaml params & injects tuned params
    DetectionConfigTuner detectionTuner = new DetectionConfigTuner(config, provider);
    config = detectionTuner.tune(tuningStartTime, tuningEndTime);
    this.detectionValidator.semanticValidation(config);
    return config;
  }

  /*
   * Create a yaml onboarding task. It runs 1 month replay and re-tune the pipeline.
   */
  private void createYamlOnboardingTask(long detectionConfigId, long tuningWindowStart, long tuningWindowEnd) {
    DetectionConfigDTO detectionConfig = this.detectionConfigDAO.findById(detectionConfigId);
    Preconditions.checkNotNull(detectionConfig,
        String.format("Cannot find detection %d to run onboarding job", detectionConfigId));

    YamlOnboardingTaskInfo info = new YamlOnboardingTaskInfo();
    info.setConfigId(detectionConfig.getId());
    if (tuningWindowStart == 0L && tuningWindowEnd == 0L) {
      // default tuning window 28 days
      tuningWindowEnd = System.currentTimeMillis();
      tuningWindowStart = tuningWindowEnd - TimeUnit.DAYS.toMillis(28);
    }
    info.setTuningWindowStart(tuningWindowStart);
    info.setTuningWindowEnd(tuningWindowEnd);
    info.setEnd(System.currentTimeMillis());

    long lastTimestamp = detectionConfig.getLastTimestamp();
    // If no value is present, set the default lookback
    if (lastTimestamp < 0) {
      lastTimestamp = info.getEnd() - ONBOARDING_REPLAY_LOOKBACK;
    }
    info.setStart(lastTimestamp);

    String taskInfoJson;
    try {
      taskInfoJson = OBJECT_MAPPER.writeValueAsString(info);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(String.format("Error while serializing %s: %s",
          YamlOnboardingTaskInfo.class.getSimpleName(), info), e);
    }

    TaskDTO taskDTO = TaskUtils.buildTask(detectionConfig.getId(), taskInfoJson, TaskConstants.TaskType.YAML_DETECTION_ONBOARD);
    long taskId = this.taskDAO.save(taskDTO);
    LOG.info("Created {} task {} with taskId {}", TaskConstants.TaskType.YAML_DETECTION_ONBOARD, taskDTO, taskId);
  }

  private Response processValidationErrorResponse(String type, String operation, String payload, ConfigValidationException e) {
    Map<String, String> responseMessage = new HashMap<>();
    LOG.warn("Validation error while {} {} with payload {}", operation, type, payload, e);
    responseMessage.put("message", "Validation Error in " + type + "! " + e.getMessage());
    responseMessage.put("more-info", e.getValidationErrorReport());
    return Response.status(Response.Status.BAD_REQUEST).entity(responseMessage).build();
  }

  private Response processServerErrorResponse(String type, String operation, String payload, Exception e) {
    Map<String, String> responseMessage = new HashMap<>();
    LOG.error("Error {} {} with payload {}", operation, type, payload, e);
    responseMessage.put("message", "Failed to create the " + type + ". Reach out to the ThirdEye team.");
    responseMessage.put("more-info", ExceptionUtils.getStackTrace(e));
    return Response.serverError().entity(responseMessage).build();
  }

  private Response processBadAuthorizationResponse(String type, String operation, String payload, NotAuthorizedException e) {
    Map<String, String> responseMessage = new HashMap<>();
    LOG.warn("Authorization error while {} {} with payload {}", operation, type, payload, e);
    responseMessage.put("message", "Authorization error! You do not have permissions to " + operation + " this " + type + " config");
    responseMessage.put("more-info", "Configure owners property in " + type + " config");
    return Response.status(Response.Status.UNAUTHORIZED).entity(responseMessage).build();
  }

  /**
   * Perform some basic validations on the create alert payload
   */
  public void validateCreateAlertYaml(Map<String, String> config) throws ConfigValidationException {
    ConfigValidationUtils.checkArgument(config.containsKey(PROP_DETECTION), String.format(MISSING_YAML_MSG, "Detection"));
    ConfigValidationUtils.checkArgument(config.containsKey(PROP_SUBSCRIPTION), String.format(MISSING_YAML_MSG, "Subscription Group"));

    // check if subscription group has subscribed to the detection
    Map<String, Object> detectionConfigMap = ConfigUtils.getMap(this.yaml.load(config.get(PROP_DETECTION)));
    String detectionName = MapUtils.getString(detectionConfigMap, PROP_DETECTION_NAME);
    Map<String, Object> subscriptionConfigMap = ConfigUtils.getMap(this.yaml.load(config.get(PROP_SUBSCRIPTION)));
    List<String> detectionNames = ConfigUtils.getList(subscriptionConfigMap.get(PROP_DETECTION_NAMES));
    ConfigValidationUtils.checkArgument(detectionNames.contains(detectionName),
        "You have not subscribed to the alert. Please configure the detectionName under the "
            + PROP_DETECTION_NAMES + " field in your subscription group.");
  }

  @POST
  @Path("/create-alert")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @PermitAll
  @ApiOperation("Use yaml to create both subscription and detection yaml. ")
  public Response createYamlAlert(
      @Auth ThirdEyePrincipal user,
      @ApiParam(value =  "a json contains both subscription and detection yaml as string")  String payload,
      @ApiParam("tuning window start time for tunable components") @QueryParam("startTime") long tuningStartTime,
      @ApiParam("tuning window end time for tunable components") @QueryParam("endTime") long tuningEndTime) {
    Map<String, String> responseMessage = new HashMap<>();
    Long detectionConfigId = null;
    Long subscriptionConfigId = null;
    boolean isAlertCreationSuccess = false;

    Map<String, String> yamls;
    try {
      // validate the payload
      validatePayload(payload);
      yamls = OBJECT_MAPPER.readValue(payload, Map.class);
      validateCreateAlertYaml(yamls);

      // Parse and save the detection config
      String detectionYaml = yamls.get(PROP_DETECTION);
      ConfigValidationUtils.checkArgument(StringUtils.isNotBlank(detectionYaml), String.format(MISSING_YAML_MSG, "Detection"));
      detectionConfigId = createDetectionConfig(detectionYaml, tuningStartTime, tuningEndTime);

      // Parse and save the subscription config
      String subscriptionYaml = yamls.get(PROP_SUBSCRIPTION);
      ConfigValidationUtils.checkArgument(StringUtils.isNotBlank(subscriptionYaml), String.format(MISSING_YAML_MSG, "Subscription group"));
      // Check if existing or new subscription group
      Map<String, Object> subscriptionYamlConfig = ConfigUtils.getMap(this.yaml.load(subscriptionYaml));
      String groupName = MapUtils.getString(subscriptionYamlConfig, PROP_SUBS_GROUP_NAME);
      List<DetectionAlertConfigDTO> alertConfigDTOS = subscriptionConfigDAO.findByPredicate(Predicate.EQ("name", groupName));
      if (!alertConfigDTOS.isEmpty()) {
        subscriptionConfigId = alertConfigDTOS.get(0).getId();
        updateSubscriptionGroup(user, subscriptionConfigId, subscriptionYaml);
      } else {
        subscriptionConfigId = createSubscriptionConfig(subscriptionYaml);
      }

      // Both the detection and subscription configs are created/updated successfully
      isAlertCreationSuccess = true;

      // Submit onboarding job to populate some anomalies
      createYamlOnboardingTask(detectionConfigId, tuningStartTime, tuningEndTime);
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(PROP_ALERT, YamlOperations.CREATING.name(), payload, e);
    } catch (NotAuthorizedException e) {
      return processBadAuthorizationResponse(PROP_ALERT, YamlOperations.UPDATING.name(), payload, e);
    } catch (Exception e) {
      return processServerErrorResponse(PROP_ALERT, YamlOperations.CREATING.name() + "/" + YamlOperations.UPDATING.name(), payload, e);
    } finally {
      if (!isAlertCreationSuccess) {
        rollbackAlert(detectionConfigId, subscriptionConfigId);
      }
    }

    responseMessage.put("message", "Alert was created successfully.");
    responseMessage.put("more-info", "Record saved with detection id " + detectionConfigId
        + " and subscription id " + subscriptionConfigId);
    responseMessage.put(PROP_DETECTION_ID, String.valueOf(detectionConfigId));
    responseMessage.put(PROP_SUBSCRIPTION_ID, String.valueOf(subscriptionConfigId));
    return Response.ok().entity(responseMessage).build();
  }

  private void rollbackDetection(Long detectionConfigId) {
    if (detectionConfigId != null) {
      LOG.warn(String.format("Rolling back detection %d", detectionConfigId));
      this.detectionConfigDAO.deleteById(detectionConfigId);
    }
  }

  private void rollbackSubscription(Long subscriptionConfigId) {
    if (subscriptionConfigId != null) {
      LOG.warn(String.format("Rolling back subscription %d", subscriptionConfigId));
      this.subscriptionConfigDAO.deleteById(subscriptionConfigId);
    }
  }

  private void rollbackAlert(Long detectionConfigId, Long subscriptionConfigId) {
    rollbackDetection(detectionConfigId);
    rollbackSubscription(subscriptionConfigId);
  }

  /**
   * Check and validate if there exists a duplicate detection name entry
   */
  private void validateDuplicateDetection(String detectionName) throws ConfigValidationException {
    List<DetectionConfigDTO> detectionConfigDTOS = this.detectionConfigDAO
        .findByPredicate(Predicate.EQ("name", detectionName));
    ConfigValidationUtils.checkArgument(detectionConfigDTOS.isEmpty(),
        String.format(DUPLICATE_KEY_MSG, PROP_DETECTION_NAME));
  }

  /**
   * Check and validate if there exists a duplicate subscription group name entry
   */
  private void validateDuplicateSubscriptionGroup(String subscriptionGroupName) throws ConfigValidationException {
    List<DetectionAlertConfigDTO> detectionAlertConfigDTOS = this.subscriptionConfigDAO
        .findByPredicate(Predicate.EQ("name", subscriptionGroupName));
    ConfigValidationUtils.checkArgument(detectionAlertConfigDTOS.isEmpty(),
        String.format(DUPLICATE_KEY_MSG, PROP_SUBS_GROUP_NAME));
  }

  private void validatePayload(String payload) throws ConfigValidationException {
    ConfigValidationUtils.checkArgument(StringUtils.isNotBlank(payload), EMPTY_PAYLOAD_MSG);
  }

  long createDetectionConfig(@NotNull String yamlDetectionConfig) throws ConfigValidationException {
    return createDetectionConfig(yamlDetectionConfig, 0, 0);
  }

  long createDetectionConfig(@NotNull String payload, long tuningStartTime, long tuningEndTime)
      throws ConfigValidationException {
    // if can't acquire alert onboarding QPS permit, throw an exception
    if (!this.onboardingRateLimiter.tryAcquire()) {
      throw new RuntimeException(String.format("Server is busy handling create detection requests. Please retry later."
          + " QPS quota: %f", this.onboardingRateLimiter.getRate()));
    }

    DetectionConfigDTO detectionConfig = buildDetectionConfigFromYaml(tuningStartTime, tuningEndTime, payload, null);
    validateDuplicateDetection(detectionConfig.getName());

    Long id = this.detectionConfigDAO.save(detectionConfig);
    Preconditions.checkNotNull(id, "Error while saving the detection pipeline");

    return id;
  }

  /**
   Set up a detection pipeline using a YAML config
   @param payload YAML config string
   @param startTime tuning window start time for tunable components
   @param endTime tuning window end time for tunable components
   @return a message contains the saved detection config id
   */
  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @ApiOperation("Set up a detection pipeline using a YAML config")
  public Response createDetectionPipelineApi(
      @ApiParam("yaml config") String payload,
      @ApiParam("tuning window start time for tunable components") @QueryParam("startTime") long startTime,
      @ApiParam("tuning window end time for tunable components") @QueryParam("endTime") long endTime) {
    Map<String, String> responseMessage = new HashMap<>();
    long detectionConfigId;
    try {
      validatePayload(payload);
      detectionConfigId = createDetectionConfig(payload, startTime, endTime);
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(PROP_DETECTION, YamlOperations.CREATING.name(), payload, e);
    } catch (Exception e) {
      return processServerErrorResponse(PROP_DETECTION, YamlOperations.CREATING.name(), payload, e);
    }

    LOG.info("Detection created with id " + detectionConfigId);
    responseMessage.put("message", "Alert was created successfully.");
    responseMessage.put("more-info", "Record saved with id " + detectionConfigId);
    return Response.ok().entity(responseMessage).build();
  }

  private void updateDetectionConfig(ThirdEyePrincipal user, long detectionID, @NotNull String payload)
      throws ConfigValidationException {
    updateDetectionConfig(user, detectionID, payload, 0, 0);
  }

  private void updateDetectionConfig(ThirdEyePrincipal user, long detectionID, @NotNull String payload, long startTime,
      long endTime) throws ConfigValidationException {
    DetectionConfigDTO existingDetectionConfig = this.detectionConfigDAO.findById(detectionID);
    Preconditions.checkNotNull(existingDetectionConfig, "Cannot find detection pipeline " + detectionID);

    authorizeUser(user, detectionID, PROP_DETECTION);
    try {
      DetectionConfigDTO updatedDetectionConfig = buildDetectionConfigFromYaml(startTime, endTime, payload, existingDetectionConfig);
      if (!updatedDetectionConfig.getName().equals(existingDetectionConfig.getName())) {
        // detection name has been renamed; ensure there are no duplicates.
        validateDuplicateDetection(updatedDetectionConfig.getName());
      }
      this.detectionConfigDAO.update(updatedDetectionConfig);
    } finally {
      // If it is to disable the pipeline then no need to do validation and parsing.
      // It is possible that the metric or dataset was deleted so the validation will fail.
      Map<String, Object> detectionConfigMap = ConfigUtils.getMap(this.yaml.load(payload));
      if (!MapUtils.getBooleanValue(detectionConfigMap, PROP_ACTIVE, true)) {
        existingDetectionConfig.setActive(false);
        existingDetectionConfig.setYaml(payload);
        this.detectionConfigDAO.update(existingDetectionConfig);
      }
    }
  }

  private boolean isServiceAccount(ThirdEyePrincipal user) {
    if (user == null || user.getSessionKey() == null) {
      return false;
    }

    List<Predicate> predicates = new ArrayList<>();
    predicates.add(Predicate.EQ(PROP_SESSION_KEY, user.getSessionKey()));
    predicates.add(Predicate.EQ(PROP_PRINCIPAL_TYPE, PROP_SERVICE));

    List<SessionDTO> sessionDTO = this.sessionDAO.findByPredicate(Predicate.AND(predicates.toArray(new Predicate[0])));
    return sessionDTO != null && !sessionDTO.isEmpty();
  }

  private void validateConfigOwner(ThirdEyePrincipal user, List<String> owners) {
    Preconditions.checkNotNull(user.getName(), "Unable to retrieve the user name from the request");
    if (owners == null || !owners.contains(user.getName())) {
      throw new NotAuthorizedException("Service account " + user.getName() + " is not authorized to access this resource.");
    }
  }

  /**
   * Enforce authorization only with service accounts to prevent risk
   * of modifying other configs when making programmatic calls.
   */
  private void authorizeUser(ThirdEyePrincipal user, long id, String authEntity) {
    if (isServiceAccount(user)) {
      if (authEntity.equals(PROP_DETECTION)) {
        DetectionConfigDTO detectionConfig = this.detectionConfigDAO.findById(id);
        validateConfigOwner(user, detectionConfig.getOwners());
      } else if (authEntity.equals(PROP_SUBSCRIPTION)) {
        DetectionAlertConfigDTO subscriptionConfig = this.subscriptionConfigDAO.findById(id);
        validateConfigOwner(user, subscriptionConfig.getOwners());
      }

      LOG.info("Service account " + user.getName() + " authorized successfully");
    }
  }

  /**
   Edit a detection pipeline using a YAML config
   @param payload YAML config string
   @param id the detection config id to edit
   @param startTime tuning window start time for tunable components
   @param endTime tuning window end time for tunable components
   @return a message contains the saved detection config id
   */
  @PUT
  @Path("/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @PermitAll
  @ApiOperation("Edit a detection pipeline using a YAML config")
  public Response updateDetectionPipelineApi(
      @Auth ThirdEyePrincipal user,
      @ApiParam("yaml config") String payload,
      @ApiParam("the detection config id to edit") @PathParam("id") long id,
      @ApiParam("tuning window start time for tunable components")  @QueryParam("startTime") long startTime,
      @ApiParam("tuning window end time for tunable components") @QueryParam("endTime") long endTime) {
    Map<String, String> responseMessage = new HashMap<>();
    try {
      validatePayload(payload);
      updateDetectionConfig(user, id, payload, startTime, endTime);
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(PROP_DETECTION, YamlOperations.UPDATING.name(), payload, e);
    } catch (NotAuthorizedException e) {
      return processBadAuthorizationResponse(PROP_DETECTION, YamlOperations.UPDATING.name(), payload, e);
    } catch (Exception e) {
      return processServerErrorResponse(PROP_DETECTION, YamlOperations.UPDATING.name(), payload, e);
    }

    LOG.info("Detection with id " + id + " updated");
    responseMessage.put("message", "Alert was updated successfully.");
    responseMessage.put("more-info", "Record updated id " + id);
    responseMessage.put(PROP_DETECTION_ID, String.valueOf(id));
    return Response.ok().entity(responseMessage).build();
  }

  private DetectionConfigDTO fetchExistingDetection(@NotNull String payload) throws ConfigValidationException {
    DetectionConfigDTO existingDetectionConfig = null;

    // Extract the detectionName from payload
    Map<String, Object> detectionConfigMap = new HashMap<>();
    detectionConfigMap.putAll(ConfigUtils.getMap(this.yaml.load(payload)));
    String detectionName = MapUtils.getString(detectionConfigMap, PROP_DETECTION_NAME);
    ConfigValidationUtils.checkArgument(StringUtils.isNotBlank(detectionName), PROP_DETECTION_NAME + " cannot be left empty");

    // Check if detection already existing
    Collection<DetectionConfigDTO> detectionConfigs = this.detectionConfigDAO
        .findByPredicate(Predicate.EQ("name", detectionName));
    if (detectionConfigs != null && !detectionConfigs.isEmpty()) {
      existingDetectionConfig = detectionConfigs.iterator().next();
    }

    return existingDetectionConfig;
  }

  long createOrUpdateDetectionConfig(ThirdEyePrincipal user, @NotNull String payload) throws ConfigValidationException {
    long detectionId;
    DetectionConfigDTO existingDetection = fetchExistingDetection(payload);
    if (existingDetection != null) {
      detectionId = existingDetection.getId();
      updateDetectionConfig(user, detectionId, payload);
    } else {
      detectionId = createDetectionConfig(payload);

      // Submit onboarding job to populate some anomalies
      createYamlOnboardingTask(detectionId, 0, 0);
    }

    return detectionId;
  }

  /**
   Set up a detection pipeline using a YAML config - create new or update existing
   @param payload YAML config string
   @return a message contains the saved detection config id
   */
  @POST
  @Path("/create-or-update")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @PermitAll
  @ApiOperation("Create a new detection config or update existing if one already exists")
  public Response createOrUpdateDetectionConfigApi(
      @Auth ThirdEyePrincipal user,
      @ApiParam("yaml config") String payload) {
    Map<String, String> responseMessage = new HashMap<>();
    long detectionConfigId;
    try {
      validatePayload(payload);

      detectionConfigId = createOrUpdateDetectionConfig(user, payload);
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(PROP_DETECTION, YamlOperations.CREATING.name() + "/" + YamlOperations.UPDATING.name(), payload, e);
    } catch (NotAuthorizedException e) {
      return processBadAuthorizationResponse(PROP_DETECTION, YamlOperations.CREATING.name() + "/" + YamlOperations.UPDATING.name(), payload, e);
    } catch (Exception e) {
      return processServerErrorResponse(PROP_DETECTION, YamlOperations.CREATING.name() + "/" + YamlOperations.UPDATING.name(), payload, e);
    }

    LOG.info("Detection Config created/updated with id " + detectionConfigId);
    responseMessage.put("message", "The alert was created/updated successfully.");
    responseMessage.put("more-info", "Record saved/updated with id " + detectionConfigId);
    return Response.ok().entity(responseMessage).build();
  }

  private DetectionAlertConfigDTO fetchExistingSubscriptionGroup(@NotNull String payload)
      throws ConfigValidationException {
    DetectionAlertConfigDTO existingSubscriptionConfig = null;

    // Extract the subscription group name from payload
    Map<String, Object> subscriptionConfigMap = ConfigUtils.getMap(this.yaml.load(payload));
    String subscriptionGroupName = MapUtils.getString(subscriptionConfigMap, PROP_SUBS_GROUP_NAME);
    ConfigValidationUtils.checkArgument(StringUtils.isNotBlank(subscriptionGroupName), "Missing property " + PROP_SUBS_GROUP_NAME
        + " in the subscription config.");

    // Check if subscription already exists
    Collection<DetectionAlertConfigDTO> subscriptionConfigs = this.subscriptionConfigDAO
        .findByPredicate(Predicate.EQ("name", subscriptionGroupName));
    if (subscriptionConfigs != null && !subscriptionConfigs.isEmpty()) {
      existingSubscriptionConfig = subscriptionConfigs.iterator().next();
    }

    return existingSubscriptionConfig;
  }

  /**
   * Update an existing subscription config or create a new one otherwise.
   */
  long createOrUpdateSubscriptionConfig(ThirdEyePrincipal user, @NotNull String payload)
      throws ConfigValidationException {
    long subscriptionId;
    DetectionAlertConfigDTO existingSubscriptionGroup = fetchExistingSubscriptionGroup(payload);
    if (existingSubscriptionGroup != null) {
      subscriptionId = existingSubscriptionGroup.getId();
      updateSubscriptionGroup(user, subscriptionId, payload);
    } else {
      subscriptionId = createSubscriptionConfig(payload);
    }

    return subscriptionId;
  }

  /**
   Set up a subscription pipeline using a YAML config - create new or update existing
   @param payload YAML config string
   @return a message contains the saved subscription config id
   */
  @POST
  @Path("/subscription/create-or-update")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @PermitAll
  @ApiOperation("Create a new subscription config or update existing if one already exists")
  public Response createOrUpdateSubscriptionConfigApi(
      @Auth ThirdEyePrincipal user,
      @ApiParam("yaml config") String payload) {
    Map<String, String> responseMessage = new HashMap<>();
    long subscriptionConfigId;
    try {
      validatePayload(payload);
      subscriptionConfigId = createOrUpdateSubscriptionConfig(user, payload);
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(PROP_DETECTION, YamlOperations.CREATING.name() + "/" + YamlOperations.UPDATING.name(), payload, e);
    } catch (NotAuthorizedException e) {
      return processBadAuthorizationResponse(PROP_DETECTION, YamlOperations.CREATING.name() + "/" + YamlOperations.UPDATING.name(), payload, e);
    } catch (Exception e) {
      return processServerErrorResponse(PROP_DETECTION, YamlOperations.CREATING.name() + "/" + YamlOperations.UPDATING.name(), payload, e);
    }

    LOG.info("Subscription config created/updated with id " + subscriptionConfigId);
    responseMessage.put("message", "The subscription group was created/updated successfully.");
    responseMessage.put("more-info", "Record saved/updated with id " + subscriptionConfigId);
    return Response.ok().entity(responseMessage).build();
  }

  public long createSubscriptionConfig(@NotNull String payload) throws ConfigValidationException {
    DetectionAlertConfigDTO alertConfig = new SubscriptionConfigTranslator(payload).translate();

    // Validate the config before saving it
    subscriptionValidator.semanticValidation(alertConfig);
    validateDuplicateSubscriptionGroup(alertConfig.getName());

    // Save the detection alert config
    Long id = this.subscriptionConfigDAO.save(alertConfig);
    Preconditions.checkNotNull(id, "Error while saving the subscription group");

    return alertConfig.getId();
  }

  @POST
  @Path("/subscription")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @ApiOperation("Create a subscription group using a YAML config")
  @SuppressWarnings("unchecked")
  public Response createSubscriptionConfigApi(
      @ApiParam("payload") String payload) {
    Map<String, String> responseMessage = new HashMap<>();
    long subscriptionConfigId;
    try {
      validatePayload(payload);
      subscriptionConfigId = createSubscriptionConfig(payload);
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(PROP_SUBSCRIPTION, YamlOperations.CREATING.name(), payload, e);
    } catch (Exception e) {
      return processServerErrorResponse(PROP_SUBSCRIPTION, YamlOperations.CREATING.name(), payload, e);
    }

    LOG.info("Notification group created with id " + subscriptionConfigId);
    responseMessage.put("message", "The subscription group was created successfully.");
    responseMessage.put("detectionAlertConfigId", String.valueOf(subscriptionConfigId));
    return Response.ok().entity(responseMessage).build();
  }

  void updateSubscriptionGroup(ThirdEyePrincipal user, long existingSubscriptionID, @NotNull String payload)
      throws ConfigValidationException {
    DetectionAlertConfigDTO existingSubscriptionConfig = this.subscriptionConfigDAO.findById(existingSubscriptionID);
    if (existingSubscriptionConfig == null) {
      throw new RuntimeException("Cannot find subscription group " + existingSubscriptionID);
    }

    authorizeUser(user, existingSubscriptionID, PROP_SUBSCRIPTION);
    DetectionAlertConfigDTO newSubscriptionConfig =
        new SubscriptionConfigTranslator(payload).translate();

    // Propagate & update watermarks to reflect changes to detectionName list in subscription config
    Map<Long, Long> updatedVectorClocks = new HashMap<>();
    Map<Long, Long> oldVectorClocks = existingSubscriptionConfig.getVectorClocks();
    Map<String, Object> properties = newSubscriptionConfig.getProperties();
    long currentTimestamp = System.currentTimeMillis();
    if (properties.get(PROP_DETECTION_CONFIG_IDS) != null) {
      for (long detectionId : ConfigUtils.getLongs(properties.get(PROP_DETECTION_CONFIG_IDS))) {
        updatedVectorClocks.put(detectionId, oldVectorClocks.getOrDefault(detectionId, currentTimestamp));
      }
    }

    newSubscriptionConfig.setId(existingSubscriptionConfig.getId());
    newSubscriptionConfig.setVectorClocks(updatedVectorClocks);

    // Validate before updating the config
    subscriptionValidator.semanticValidation(newSubscriptionConfig);
    if (!newSubscriptionConfig.getName().equals(existingSubscriptionConfig.getName())) {
      // Subscription group has been renamed; ensure there are no duplicates.
      validateDuplicateSubscriptionGroup(newSubscriptionConfig.getName());
    }

    // Save the updated subscription config
    int subscriptionConfigId = this.subscriptionConfigDAO.update(newSubscriptionConfig);
    if (subscriptionConfigId <= 0) {
      throw new RuntimeException("Failed to update the detection alert config.");
    }
  }

  @PUT
  @Path("/subscription/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @PermitAll
  @ApiOperation("Edit a subscription group using a YAML config")
  public Response updateSubscriptionGroupApi(
      @Auth ThirdEyePrincipal user,
      @ApiParam("payload") String payload,
      @ApiParam("the detection alert config id to edit") @PathParam("id") long id) {
    Map<String, String> responseMessage = new HashMap<>();
    try {
      validatePayload(payload);
      updateSubscriptionGroup(user, id, payload);
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(PROP_SUBSCRIPTION, YamlOperations.UPDATING.name(), payload, e);
    } catch (NotAuthorizedException e) {
      return processBadAuthorizationResponse(PROP_SUBSCRIPTION, YamlOperations.UPDATING.name(), payload, e);
    } catch (Exception e) {
      return processServerErrorResponse(PROP_SUBSCRIPTION, YamlOperations.UPDATING.name(), payload, e);
    }

    LOG.info("Subscription group with id " + id + " updated");
    responseMessage.put("message", "The subscription group was updated successfully.");
    responseMessage.put("detectionAlertConfigId", String.valueOf(id));
    return Response.ok().entity(responseMessage).build();
  }

  @POST
  @Path("/preview")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @ApiOperation("Preview the anomaly detection result of a YAML configuration")
  public Response yamlPreviewApi(
      @QueryParam("start") long start,
      @QueryParam("end") long end,
      @QueryParam("tuningStart") long tuningStart,
      @QueryParam("tuningEnd") long tuningEnd,
      @ApiParam("jsonPayload") String payload) throws ConfigValidationException {
    validatePayload(payload);
    return runPreview(start, end, tuningStart, tuningEnd, payload, null);
  }

  @POST
  @Path("/preview/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @ApiOperation("Preview the anomaly detection result of a YAML configuration, with an existing config and historical anomalies")
  public Response yamlPreviewWithHistoricalAnomalies(
      @PathParam("id") long id,
      @QueryParam("start") long start,
      @QueryParam("end") long end,
      @QueryParam("tuningStart") long tuningStart,
      @QueryParam("tuningEnd") long tuningEnd,
      @ApiParam("jsonPayload") String payload) throws ConfigValidationException {
    validatePayload(payload);
    DetectionConfigDTO existingConfig = this.detectionConfigDAO.findById(id);
    Preconditions.checkNotNull(existingConfig, "can not find existing detection config " + id);
    return runPreview(start, end, tuningStart, tuningEnd, payload, existingConfig);
  }

  private Response runPreview(long start, long end,
      long tuningStart, long tuningEnd, @NotNull String payload, DetectionConfigDTO existingConfig) {
    long ts = System.currentTimeMillis();
    Map<String, String> responseMessage = new HashMap<>();
    DetectionPipelineResult result;
    Future<DetectionPipelineResult> future = null;
    try {
      DetectionConfigDTO detectionConfig;
      if (existingConfig == null) {
        detectionConfig = buildDetectionConfigFromYaml(tuningStart, tuningEnd, payload, null);
        detectionConfig.setId(Long.MAX_VALUE);
      } else {
        detectionConfig = buildDetectionConfigFromYaml(tuningStart, tuningEnd, payload, existingConfig);
      }

      Preconditions.checkNotNull(detectionConfig);
      DetectionPipeline pipeline = this.loader.from(this.provider, detectionConfig, start, end);
      future = this.executor.submit(pipeline::run);
      result = future.get(this.previewTimeout, TimeUnit.MILLISECONDS);
      LOG.info("Preview successful, used {} milliseconds", System.currentTimeMillis() - ts);
      return Response.ok(result).build();
    } catch (ConfigValidationException e) {
      return processValidationErrorResponse(YamlOperations.PREVIEW.name(), YamlOperations.RUNNING.name(), payload, e);
    } catch (TimeoutException e) {
      responseMessage.put("message", "Preview has timed out");
      return Response.serverError().entity(responseMessage).build();
    } catch (Exception e) {
      LOG.error("Error running preview with payload " + payload, e);
      StringBuilder sb = new StringBuilder();
      // show more stack message to frontend for debugging
      getErrorMessage(0, 5, e, sb);
      responseMessage.put("message", "Failed to run the preview.");
      responseMessage.put("more-info", sb.toString());
      return Response.serverError().entity(responseMessage).build();
    } finally {
      // stop the preview
      if (future != null) {
        future.cancel(true);
      }
    }
  }

  private void getErrorMessage(int curLevel, int totalLevel, Throwable e, StringBuilder sb) {
    if (curLevel <= totalLevel && e != null) {
      sb.append("==");
      if (e.getMessage() != null) {
        sb.append(e.getMessage());
      }
      getErrorMessage(curLevel + 1, totalLevel, e.getCause(), sb);
    }
  }

  @POST
  @Path("/preview/baseline")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.TEXT_PLAIN)
  @ApiOperation("Get baseline from YAML configuration")
  /* TODO: Will return baseline from yamlPreviewApi together with detection in the future. */
  public Response yamlPreviewBaselineApi(
      @QueryParam("start") long start,
      @QueryParam("end") long end,
      @QueryParam("urn") @NotNull String urn,
      @QueryParam("tuningStart") long tuningStart,
      @QueryParam("tuningEnd") long tuningEnd,
      @ApiParam("jsonPayload") @NotNull String payload,
      @QueryParam("ruleName") String ruleName) {
    try {
      validatePayload(payload);
      DetectionConfigDTO detectionConfig = buildDetectionConfigFromYaml(tuningStart, tuningEnd, payload, null);
      Preconditions.checkNotNull(detectionConfig);
      detectionConfig.setId(Long.MAX_VALUE);

      // There is a side effect to update detectionConfig when loading the pipeline.
      this.loader.from(this.provider, detectionConfig, start, end);
      TimeSeries baseline = getBaseline(detectionConfig, start, end, urn, ruleName);

      return Response.ok(makeTimeSeriesMap(baseline)).build();
    } catch (Exception e) {
      LOG.error("Error getting baseline with payload " + payload, e);
    }
    return Response.ok().build();
  }

  /**
   * Returns a map of time/baseline/current/upper/lower time series derived from the TimeSeries.
   *
   * @param baseline Baseline values.
   * @return map of time/baseline/current/upper/lower time series.
   */
  private static Map<String, List<? extends Number>> makeTimeSeriesMap(TimeSeries baseline) {
    Map<String, List<? extends Number>> output = new HashMap<>();
    // time and baseline are mandatory
    output.put(COL_TIME, baseline.getTime().toList());
    output.put(COL_VALUE, baseline.getPredictedBaseline().toList());
    if (baseline.getDataFrame().contains(COL_CURRENT)) {
      output.put(COL_CURRENT, baseline.getCurrent().toList());
    }
    if (baseline.getDataFrame().contains(COL_UPPER_BOUND)) {
      output.put(COL_UPPER_BOUND, baseline.getPredictedUpperBound().toList());
    }
    if (baseline.getDataFrame().contains(COL_LOWER_BOUND)) {
      output.put(COL_LOWER_BOUND, baseline.getPredictedLowerBound().toList());
    }
    return output;
  }

  /**
   * Get the baselines for metric urn.
   * If there are multiple rules return the first rule's baseline.
   * TODO: The baseline should be calculated together with detection in the future.
   *
   * @param detectionConfig The detection configuration.
   * @param start Start time for baseline calculation.
   * @param end End time for baseline calculation.
   * @param urn The metric urn.
   * @param rule The rule name. If not provided then find the first rule.
   * @return The baseline for the urn.
   */
  private TimeSeries getBaseline(DetectionConfigDTO detectionConfig, long start, long end, String urn, String rule) {
    MetricEntity metric = MetricEntity.fromURN(urn);
    MetricSlice slice = MetricSlice.from(metric.getId(), start, end, metric.getFilters(), MetricSlice.NATIVE_GRANULARITY);

    Optional<BaselineProvider> provider =  detectionConfig.getComponents().entrySet().stream()
        .filter(x -> x.getValue() instanceof BaselineProvider && (rule == null || rule.isEmpty() || x.getKey().startsWith(rule)))
        .map(x -> (BaselineProvider) x.getValue())
        .findFirst();

    if (provider.isPresent()) {
      return provider.get().computePredictedTimeSeries(slice);
    }

    return TimeSeries.empty();
  }

  /**
   * Toggle active/inactive for given detection
   *
   * @param detectionId detection config id (must exist)
   * @param active value to set for active field in detection config
   */
  @PUT
  @Path("/activation/{id}")
  @ApiOperation("Make detection active or inactive, given id")
  @Produces(MediaType.APPLICATION_JSON)
  public Response toggleActivation(
      @ApiParam("Detection configuration id for the alert") @NotNull @PathParam("id") long detectionId,
      @ApiParam("Active status you want to set for the alert") @NotNull @QueryParam("active") boolean active) {
    Map<String, String> responseMessage = new HashMap<>();
    try {
      DetectionConfigDTO config = this.detectionConfigDAO.findById(detectionId);
      if (config == null) {
        throw new IllegalArgumentException(String.format("Cannot find config %d", detectionId));
      }

      // update state
      config.setActive(active);
      this.detectionConfigDAO.update(config);
      responseMessage.put("message", "Alert activation toggled to " + active + " for detection id " + detectionId);
    } catch (Exception e) {
      LOG.error("Error toggling activation on detection id " + detectionId, e);
      responseMessage.put("message", "Failed to toggle activation: " + e.getMessage());
      return Response.serverError().entity(responseMessage).build();
    }

    LOG.info("Alert activation toggled to {} for detection id {}", active , detectionId);
    return Response.ok(responseMessage).build();
  }

  /**
   * Implements filter for detection config.
   * Client can filter by dataset or metric or both.  It only filters if they are not null in params and config
   *
   * @param detectionConfig The detection configuration after being enhanced by DetectionConfigFormatter::format.
   * @param dataset The dataset param passed by the client in the REST API call.
   * @param metric The metric param passed by the client in the REST API call.
   * @return true if the config matches query params that are passed by client.
   */
  private boolean filterConfigsBy(Map<String, Object> detectionConfig, String dataset, String metric) {
    List datasetList = (List) detectionConfig.get("datasetNames");
    String metricString = (String) detectionConfig.get("metric");
    // defaults are true so we filter only if the params are passed
    boolean metricMatch = true;
    boolean datasetMatch = true;
    // check metric only if it was passed
    if (metric != null ) {
      // equals method should not be called on null
      metricMatch = metricString != null && metricString.equals(metric);
    }
    // check dataset only if it was passed
    if (dataset != null) {
      // contains method should not be called on null
      datasetMatch = datasetList != null && datasetList.contains(dataset);
    }
    // config should satisfy both filters
    return metricMatch && datasetMatch;
  }

  /**
   * Handles parse errors individually when formatting configs.
   *
   * @param config The detection configuration to be enhanced by DetectionConfigFormatter::format.
   * @return enhanced configuration or null if the parse failed, logs failure for debugging.
   */
  private Map<String, Object> formatConfigOrNull(DetectionConfigDTO config) {
    try {
      return this.detectionConfigFormatter.format(config);
    } catch (Exception e){
      LOG.warn("Error parsing config id: {}", config.getId());
    }
    return null;
  }

  /**
   * Query all detection yaml configurations and optionally filter, then format as JSON and enhance with
   * detection config id, isActive, and createdBy information
   *
   * NOTE: it is limited to list and filter the most recent 100 alerts only due to possible OOM issues.
   *
   * @param dataset The dataset param passed by the client in the REST API call.
   * @param metric The metric param passed by the client in the REST API call.
   * @return the yaml configuration converted in to JSON, with enhanced information from detection config DTO.
   */
  @Deprecated
  private List<Map<String, Object>> queryDetectionConfigurations(String dataset, String metric) {
    List<Map<String, Object>> yamls;
    if (dataset == null && metric == null) {
      yamls = this.detectionConfigDAO
          .list(100, 0)
          .parallelStream()
          .map(config -> formatConfigOrNull(config))
          .filter(c -> c!= null)
          .collect(Collectors.toList());
    } else {
      yamls = this.detectionConfigDAO
          .list(100, 0)
          .parallelStream()
          .map(config -> formatConfigOrNull(config))
          .filter(c -> c!= null)
          .filter(y -> filterConfigsBy(y, dataset, metric))
          .collect(Collectors.toList());
    }
    return yamls;
  }

  /**
   * List all yaml configurations as JSON enhanced with detection config id, isActive and createBy information.
   * @param dataset the dataset to filter results by (optional)
   * @param metric the metric to filter results by (optional)
   * @return the yaml configuration converted in to JSON, with enhanced information from detection config DTO.
   */
  @GET
  @Path("/list")
  @ApiOperation(
      "Get the list of all detection YAML configurations as JSON enhanced with additional information, optionally filtered."
          + "IMPORTANT NOTE: This endpoint is deprecated because it is not scalable. Please use the /alerts endpoint instead. "
          + "For now, it is limited to list and filter the most recent 100 alerts only. ")
  @Produces(MediaType.APPLICATION_JSON)
  @Deprecated
  public Response listYamls(
      @ApiParam("Dataset the detection configurations should be filtered by") @QueryParam("dataset") String dataset,
      @ApiParam("Metric the detection configurations should be filtered by") @QueryParam("metric") String metric){
    Map<String, String> responseMessage = new HashMap<>();
    List<Map<String, Object>> yamls;
    try {
      yamls = queryDetectionConfigurations(dataset, metric);
    } catch (Exception e) {
      LOG.warn("Error while fetching detection yaml configs.", e.getMessage());
      responseMessage.put("message", "Failed to fetch all the detection configurations.");
      responseMessage.put("more-info", ExceptionUtils.getStackTrace(e));
      return Response.serverError().entity(responseMessage).build();
    }

    LOG.info("Successfully returned " + yamls.size() + " detection configs.");
    return Response.ok(yamls).build();
  }

  /**
   * Api to trigger a notification alert. Mostly used for ad-hoc testing.
   * Alert will be sent only if there are new anomalies since the last watermark.
   * Watermarks will be updated after notifying anomalies if any.
   */
  @PUT
  @Path("/notify/{id}")
  @ApiOperation("Send notification email for detection alert config")
  public Response triggerNotification(
      @ApiParam("Subscription configuration id for the alert") @NotNull @PathParam("id") long subscriptionId) {
    LOG.info("Triggering subscription task with id " + subscriptionId);
    Map<String, String> responseMessage = new HashMap<>();
    try {
      // Build the task context
      ThirdEyeAnomalyConfiguration config = new ThirdEyeAnomalyConfiguration();
      config.setAlerterConfiguration(alerterConfig);
      TaskContext taskContext = new TaskContext();
      taskContext.setThirdEyeAnomalyConfiguration(config);

      // Run the notification task. This will update the subscription watermark as well.
      DetectionAlertTaskInfo taskInfo = new DetectionAlertTaskInfo(subscriptionId);
      TaskRunner taskRunner = new DetectionAlertTaskRunner();
      taskRunner.execute(taskInfo, taskContext);
    } catch (Exception e) {
      LOG.error("Exception while triggering the notification task with id " + subscriptionId, e);
      responseMessage.put("message", "Failed to trigger the notification for " + subscriptionId);
      responseMessage.put("more-info", ExceptionUtils.getStackTrace(e));
      return Response.serverError().entity(responseMessage).build();
    }

    LOG.info("Subscription with id " + subscriptionId + " triggered successfully");
    responseMessage.put("message", "Subscription was triggered successfully for " + subscriptionId);
    responseMessage.put("detectionAlertConfigId", String.valueOf(subscriptionId));
    return Response.ok().entity(responseMessage).build();
  }
}
