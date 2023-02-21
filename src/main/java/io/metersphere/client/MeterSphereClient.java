package io.metersphere.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.metersphere.ResultHolder;
import io.metersphere.commons.constants.ApiUrlConstants;
import io.metersphere.commons.constants.RequestMethod;
import io.metersphere.commons.exception.MeterSphereException;
import io.metersphere.commons.model.*;
import io.metersphere.commons.utils.HttpClientConfig;
import io.metersphere.commons.utils.HttpClientUtil;
import io.metersphere.commons.utils.LogUtil;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.http.entity.ContentType;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeterSphereClient {


    private static final String ACCEPT = "application/json;charset=UTF-8";

    private final String accessKey;
    private final String secretKey;
    private final String endpoint;

    private final static ExecutorService executorService = Executors.newFixedThreadPool(5);

    public MeterSphereClient(String accessKey, String secretKey, String endpoint) {

        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
    }

    /*校验账号*/
    public String checkUser() {
        ResultHolder getUserResult = call(ApiUrlConstants.USER_INFO);
        if (!getUserResult.isSuccess()) {
            throw new MeterSphereException(getUserResult.getMessage());
        }
        return getUserResult.getData().toString();
    }

    /*获取组织下工作空间*/
    public List<WorkspaceDTO> getWorkspace() {
        ResultHolder result = call(ApiUrlConstants.LIST_USER_WORKSPACE);
        String list = JSON.toJSONString(result.getData());
        LogUtil.info("用户所属工作空间" + list);
        return JSON.parseArray(list, WorkspaceDTO.class);
    }

    /*获取工作空间下项目列表*/
    public List<ProjectDTO> getProjectIds(String workspaceId) {
        String userId = this.checkUser();
        HashMap<String, Object> params = new HashMap<>();
        params.put("workspaceId", workspaceId);
        params.put("userId", userId);
        ResultHolder result = call(ApiUrlConstants.PROJECT_LIST_ALL, RequestMethod.POST, params);
        String listJson = JSON.toJSONString(result.getData());
        LogUtil.info("用户所属项目" + listJson);
        return JSON.parseArray(listJson, ProjectDTO.class);

    }

    /*查询该项目下所有测试用例(接口+性能)*/
    public List<TestCaseDTO> getTestCases(String projectId) {
        CountDownLatch count = new CountDownLatch(4);
        List<TestCaseDTO> result = new CopyOnWriteArrayList<>();
        executorService.submit(() -> {
            try {
                ResultHolder perfResult = call(ApiUrlConstants.PERFORMANCE_LIST_PROJECT + "/" + projectId);
                result.addAll(JSON.parseArray(JSON.toJSONString(perfResult.getData()), TestCaseDTO.class));
            } finally {
                count.countDown();
            }
        });
        executorService.submit(() -> {
            try {
                ResultHolder apiCaseResult = call(ApiUrlConstants.API_CASE_LIST_PROJECT + "/" + projectId);
                result.addAll(JSON.parseArray(JSON.toJSONString(apiCaseResult.getData()), TestCaseDTO.class));
            } finally {
                count.countDown();
            }
        });
        executorService.submit(() -> {
            try {
                ResultHolder apiScenarioResult = call(ApiUrlConstants.API_SCENARIO_LIST_PROJECT + "/" + projectId);
                result.addAll(JSON.parseArray(JSON.toJSONString(apiScenarioResult.getData()), TestCaseDTO.class));
            } finally {
                count.countDown();
            }
        });
        executorService.submit(() -> {
            try {
                HashMap<Object, Object> params = new HashMap<>();
                params.put("projectId", projectId);
                ResultHolder uiResult = call(ApiUrlConstants.UI_LIST_PROJECT, RequestMethod.POST, params);
                List<TestCaseDTO> c = JSON.parseArray(JSON.toJSONString(uiResult.getData()), TestCaseDTO.class);
                c.forEach(ui -> ui.setType("UI场景"));
                result.addAll(c);
            } finally {
                count.countDown();
            }
        });
        try {
            count.await();
            LogUtil.debug("该项目下的所有的测试" + JSON.toJSONString(result));
        } catch (Exception e) {
            LogUtil.error(e);
        }

        return result;
    }

    /*单独执行所选测试环境列表*/
    public List<ApiTestEnvironmentDTO> getEnvironmentIds(String projectId) {
        ResultHolder result = call(ApiUrlConstants.ENVIRONMEN_LIST + "/" + projectId);
        String listJson = JSON.toJSONString(result.getData());
        LogUtil.debug("该项目下的环境列表" + listJson);
        return JSON.parseArray(listJson, ApiTestEnvironmentDTO.class);
    }

    /*添加新的环境*/
    public String addEnvironment(String projectId, String name, String config) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("name", name);
        params.put("id", null);
        params.put("config", config);

        HttpClientUtil.BodyPart bodyPart = new HttpClientUtil.BodyPart();
        bodyPart.setName("request");
        bodyPart.setFilename("blob");
        bodyPart.setBodyString(JSON.toJSONString(params));
        bodyPart.setContentType(ContentType.APPLICATION_JSON.getMimeType());
        ResultHolder result = call(ApiUrlConstants.ENVIRONMEN_ADD, RequestMethod.POST_FORM_DATA, Collections.singletonList(bodyPart));
        LogUtil.debug("添加环境结果" + result.getData());
        return JSON.toJSONString(result.getData());
    }

    public String deleteEnvironment(String environmentId) {
        ResultHolder result = call(ApiUrlConstants.ENVIRONMEN_DELETE + "/" + environmentId);
        return JSON.toJSONString(result.getData());
    }

    /*查询该项目下所有测试计划*/
    public List<TestPlanDTO> getTestPlanIds(String projectId, String workspaceId) {
        ResultHolder result = call(ApiUrlConstants.PLAN_LIST_ALL + "/" + projectId + "/" + workspaceId);
        String listJson = JSON.toJSONString(result.getData());
        LogUtil.debug("该项目下的所有的测试计划" + listJson);
        return JSON.parseArray(listJson, TestPlanDTO.class);
    }

    /*资源池列表*/
    public List<EnvironmentPoolDTO> getPoolEnvironmentIds() {
        ResultHolder result = call(ApiUrlConstants.TEST_POOL);
        String listJson = JSON.toJSONString(result.getData());
        LogUtil.debug("该项目下的资源池列表" + listJson);
        return JSON.parseArray(listJson, EnvironmentPoolDTO.class);
    }

    /*执行测试计划*/
    public String exeTestPlan(String projectId, String testPlanId, String mode, String resourcePoolId) {
        String userId = this.checkUser();
        HashMap<String, Object> params = new HashMap<>();
        params.put("testPlanId", testPlanId);
        params.put("projectId", projectId);
        params.put("triggerMode", "API");
        params.put("userId", userId);
        params.put("mode", StringUtils.isBlank(mode) ? "serial" : mode);
        params.put("reportType", "iddReport");
        params.put("onSampleError", false);
        params.put("requestOriginator", "TEST_PLAN");
        params.put("executionWay", "RUN");
        if (StringUtils.isEmpty(resourcePoolId)) {
            params.put("runWithinResourcePool", false);
        } else {
            params.put("runWithinResourcePool", true);
            params.put("resourcePoolId", resourcePoolId);
        }
        ResultHolder result = call(ApiUrlConstants.TEST_PLAN, RequestMethod.POST, params);
        if (result.getData() instanceof String) {
            return (String) result.getData();
        }
        return JSON.toJSONString(result.getData());
    }

    /*查询测试计划报告状态*/
    public String getStatus(String testPlanId) {
        ResultHolder result = call(ApiUrlConstants.TEST_PLAN_STATUS + "/" + testPlanId.replace('"', ' ').trim());
        return JSON.toJSONString(result.getData());
    }

    public String runUiTest(String testCaseId, String projectId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID().toString());
        List<String> ids = new ArrayList<>();
        ids.add(testCaseId);
        params.put("ids", ids);
        params.put("projectId", projectId);
        params.put("triggerMode", "API");
        HashMap<String, Object> uiConfigParams = new HashMap<>();
        uiConfigParams.put("mode", "serial");
        uiConfigParams.put("reportType", "iddReport");
        uiConfigParams.put("browser", "CHROME");
        uiConfigParams.put("headlessEnabled", true);
        params.put("uiConfig", uiConfigParams);
        ResultHolder result = call(ApiUrlConstants.UI_RUN, RequestMethod.POST, params);
        return JSON.toJSONString(result.getData());
    }

    public String getApiTestCaseReport(String id) {
        if (StringUtils.isEmpty(id)) {
            id = UUID.randomUUID().toString();
        }
        ResultHolder result = call(ApiUrlConstants.API_TES_RESULT + "/" + id.replace('"', ' ').trim());
        String listJson = JSON.toJSONString(result.getData());
        JSONObject jsonObject = JSONObject.parseObject(listJson);
        return jsonObject.getString("execResult");
    }

    public String getApiTestState(String reportId) {
        String newReportId = reportId.replace("\"", "");
        ResultHolder result = call(ApiUrlConstants.API_GET + "/" + newReportId);
        String listJson = JSON.toJSONString(result.getData());
        JSONObject jsonObject = JSONObject.parseObject(listJson);
        return jsonObject.getString("status");
    }

    public String getUiTestState(String reportId) {
        String newReportId = reportId.replace("\"", "");
        ResultHolder result = call(ApiUrlConstants.UI_GET + "/" + newReportId);
        String listJson = JSON.toJSONString(result.getData());
        JSONObject jsonObject = JSONObject.parseObject(listJson);
        return jsonObject.getString("status");
    }

    /*单独执行场景测试*/
    public String runScenario(TestCaseDTO testCaseDTO, String id, String type, RunModeConfig config) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID().toString());
        params.put("projectId", id);
        params.put("ids", Arrays.asList(testCaseDTO.getId()));
        params.put("config", config);
        ResultHolder result;
        if (type.equals("scenario")) {
            result = call(ApiUrlConstants.API_AUTOMATION_RUN_SINGLE, RequestMethod.POST, params);
        } else {
            params.put("planCaseIds", Arrays.asList(testCaseDTO.getId()));
            params.put("planScenarioId", testCaseDTO.getId());
            result = call(ApiUrlConstants.API_AUTOMATION_RUN, RequestMethod.POST, params);
        }
        return JSON.parseArray(JSON.toJSONString(result.getData())).getJSONObject(0).getString("reportId");
    }

    public String getApiScenario(String id) {
        if (id.equals("") || id == null) {
            id = UUID.randomUUID().toString();
        }
        ResultHolder result = call(ApiUrlConstants.API_AUTOMATION_GETAPISCENARIO + "/" + id.replace('"', ' ').trim());
        String listJson = JSON.toJSONString(result.getData());
        JSONObject jsonObject = JSONObject.parseObject(listJson);
        return jsonObject.getString("status");
    }

    /*单独执行接口定义*/
    public void runDefinition(TestCaseDTO testCaseDTO, String runMode, String testPlanId, String testCaseId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("caseId", testCaseId);
        params.put("reportId", UUID.randomUUID().toString());
        params.put("runMode", runMode);
        params.put("testPlanId", testPlanId);
        params.put("triggerMode", "API");
        call(ApiUrlConstants.API_DEFINITION_RUN, RequestMethod.POST, params);

    }

    public String getDefinition(String id) {
        if (id.equals("") || id == null) {
            id = UUID.randomUUID().toString();
        }
        ResultHolder result = call(ApiUrlConstants.API_DEFINITION + "/" + id.replace('"', ' ').trim());
        String listJson = JSON.toJSONString(result.getData());
        JSONObject jsonObject = JSONObject.parseObject(listJson);
        return jsonObject.getString("status");
    }

    public String getShareInfo(Map<String, String> params) {
        ResultHolder result = call(ApiUrlConstants.API_SHARE_GENERATE, RequestMethod.POST, params);
        JSONObject jsonObject = JSONObject.parseObject(JSON.toJSONString(result.getData()));
        return jsonObject.getString("shareUrl");
    }

    /*单独执行性能测试*/
    public String runPerformanceTest(String testCaseId, String testPlanId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("id", testCaseId);
        params.put("testPlanLoadId", testCaseId);
        params.put("triggerMode", "API");
        ResultHolder result;
        result = call(ApiUrlConstants.PERFORMANCE_RUN, RequestMethod.POST, params);
        String listJson = JSON.toJSONString(result.getData());
        return listJson.replace('"', ' ').trim();
    }

    public void updateStateLoad(String testPlanId, String testCaseId, String state) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("testPlanId", testPlanId);
        params.put("loadCaseId", testCaseId);
        params.put("status", state);
        ResultHolder result;
        result = call(ApiUrlConstants.PERFORMANCE_RUN_TEST_PLAN_STATE, RequestMethod.POST, params);
        JSON.toJSONString(result.getData());
    }

    public String getPerformanceTestState(String testCaseId) {
        ResultHolder result = call(ApiUrlConstants.PERFORMANCE_GET + "/" + testCaseId);
        String listJson = JSON.toJSONString(result.getData());
        JSONObject jsonObject = JSONObject.parseObject(listJson);
        return jsonObject.getString("status");
    }

    public void changeState(String id, String status) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("status", status);
        call(ApiUrlConstants.CHANGE_STATE, RequestMethod.POST, params);
    }

    /*查询站点*/
    public String getBaseInfo() {
        BaseSystemConfigDTO baseSystemConfigDTO = new BaseSystemConfigDTO();
        ResultHolder result = call(ApiUrlConstants.BASE_INFO);
        String listJson = JSON.toJSONString(result.getData());
        JSONObject jsonObject = JSONObject.parseObject(listJson);
        return jsonObject.getString("url");
    }

    private ResultHolder call(String url) {
        return call(url, RequestMethod.GET, null);
    }

    private ResultHolder call(String url, RequestMethod requestMethod, Object params) {
        url = this.endpoint + url;
        String responseJson;

        HttpClientConfig config = auth();
        if (requestMethod.equals(RequestMethod.GET)) {
            responseJson = HttpClientUtil.get(url, config);
        } else if (requestMethod.equals(RequestMethod.POST_FORM_DATA)) {
            responseJson = HttpClientUtil.post(url, (List<HttpClientUtil.BodyPart>) params, config);
        } else {
            responseJson = HttpClientUtil.post(url, JSON.toJSONString(params), config);
        }

        ResultHolder result = JSON.parseObject(responseJson, ResultHolder.class);
        if (!result.isSuccess()) {
            throw new MeterSphereException(result.getMessage());
        }
        return JSON.parseObject(responseJson, ResultHolder.class);
    }

    private HttpClientConfig auth() {
        HttpClientConfig httpClientConfig = new HttpClientConfig();
        httpClientConfig.addHeader("Accept", ACCEPT);
        httpClientConfig.addHeader("AccessKey", accessKey);
        String signature;
        try {
            signature = aesEncrypt(accessKey + "|" + UUID.randomUUID().toString() + "|" + System.currentTimeMillis(), secretKey, accessKey);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            throw new MeterSphereException("签名失败: " + e.getMessage());
        }
        httpClientConfig.addHeader("signature", signature);
        return httpClientConfig;
    }

    private static String aesEncrypt(String src, String secretKey, String iv) throws Exception {
        byte[] raw = secretKey.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKeySpec = new SecretKeySpec(raw, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec iv1 = new IvParameterSpec(iv.getBytes());
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, iv1);
        byte[] encrypted = cipher.doFinal(src.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeBase64String(encrypted);
    }


    public boolean checkLicense() {
        ResultHolder result = call(ApiUrlConstants.API_LICENSE_VALIDATE);
        JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
        return StringUtils.equals("valid", jsonObject.getString("status"));
    }
}

