package com.baicizhan.mall;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.metersphere.client.MeterSphereClient;
import io.metersphere.commons.model.ApiTestEnvironmentDTO;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Xie Wangyi(xiewangyi@baicizhan.com)
 */
public class EnvironmentManager {
    private MeterSphereClient client;

    public EnvironmentManager(MeterSphereClient client) {
        this.client = client;
    }

    public String copyEnvironmentAndSetVariables(String projectId, String baseEnvironmentName, String newEnvironmentName, Map<String, String> variableOverrideMap) throws LogicException {
        ApiTestEnvironmentDTO baseEnvironment = findEnvironmentByName(projectId, baseEnvironmentName);

        JSONObject config = (JSONObject) JSON.parse(baseEnvironment.getConfig());
        JSONArray variables = (JSONArray) ((JSONObject) config.get("commonConfig")).get("variables");

        //先更新已经存在的变量，并从variableOverrideMap中删除
        Set<String> existingVariables = new HashSet<>();
        variableOverrideMap.forEach((key, value) -> {
            JSONObject existingVariable = variables.stream()
                    .map(ec -> (JSONObject) ec)
                    .filter(ec -> ec.get("name") != null && ec.get("name").equals(key)).findFirst().orElse(null);

            if (existingVariable != null) {
                existingVariable.put("value", value);
                existingVariables.add(key);
            }
        });
        existingVariables.forEach(variableOverrideMap::remove);

        //再append新增的variables
        AtomicInteger num = new AtomicInteger(variables.size());
        variableOverrideMap.forEach((key, value) -> {
            JSONObject newVariable = new JSONObject();
            newVariable.put("name", key);
            newVariable.put("value", value);
            newVariable.put("type", "CONSTANT");
            newVariable.put("enable", true);
            newVariable.put("id", UUID.randomUUID().toString().toLowerCase());
            newVariable.put("num", num.incrementAndGet());
            newVariable.put("scope", "api");
            variables.add(newVariable);
        });

        return client.addEnvironment(projectId, newEnvironmentName, JSON.toJSONString(config));
    }

    public String deleteEnvironment(String projectId, String environmentName) throws LogicException {
        ApiTestEnvironmentDTO environment = findEnvironmentByName(projectId, environmentName);
        return this.client.deleteEnvironment(environment.getId());
    }

    public ApiTestEnvironmentDTO findEnvironmentByName(String projectId, String environmentName) throws LogicException {
        List<ApiTestEnvironmentDTO> environments = client.getEnvironmentIds(projectId);
        if (environments == null) {
            throw new LogicException("环境[" + environmentName + "]没有找到");
        }
        ApiTestEnvironmentDTO baseEnvironment = environments.stream().filter(e -> e.getName() != null && e.getName().equals(environmentName)).findFirst().orElse(null);
        if (baseEnvironment == null) {
            throw new LogicException("环境[" + environmentName + "]没有找到");
        }
        return baseEnvironment;
    }
}
