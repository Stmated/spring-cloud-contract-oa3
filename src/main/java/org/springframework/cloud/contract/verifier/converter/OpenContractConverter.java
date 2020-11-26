package org.springframework.cloud.contract.verifier.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Iterables;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.contract.spec.Contract;

import java.io.File;
import java.util.*;


/**
 * Created by John Thompson on 5/24/18.
 */
public class OpenContractConverter extends AbstractYamlContractConverter<Collection<JsonNode>> {

    private static final Logger log = LoggerFactory.getLogger(OpenContractConverter.class);

    public static final OpenContractConverter INSTANCE = new OpenContractConverter();
    public static final String SERVICE_NAME_KEY = "scc.enabled.servicenames";

    private static final String[] OPENAPI_OPERATIONS = new String[]{"get", "put", "head", "post", "delete", "patch", "options", "trace"};

    @Override
    protected boolean isAcceptedRootJsonNode(JsonNode node) {
        return StringUtils.isNotBlank(node.path("openapi").asText(null));
    }

    @Override
    public Collection<Contract> convertFrom(File file) {

        final var sccContracts = new ArrayList<Contract>();
        for (final var node : this.readNodes(file)) {
            this.readOpenApiContract(node, sccContracts);
        }

        return sccContracts;
    }

    private void readOpenApiContract(JsonNode spec, Collection<Contract> sccContracts) {
        this.iterateFields(spec.path("paths"), (pathKey, pathNode) -> {

            for (final var operationKey : OPENAPI_OPERATIONS) {

                final var operationNode = pathNode.path(operationKey);
                this.iterateArray(operationNode.path("x-contracts"), (operationContractNode) -> {

                    final var contractServiceName = operationContractNode.path("serviceName").asText(null);

                    if (checkServiceEnabled(contractServiceName)) {

                        final var yamlContract = this.readYamlContract(operationContractNode);
                        final var contractId = operationContractNode.path("contractId").asText(null);

                        this.readOpenApiContractForOperation(pathKey, operationKey, operationNode, contractId, yamlContract, sccContracts);
                    }
                });
            }
        });
    }


    private void readOpenApiContractForOperation(String pathKey, String operationKey, JsonNode operationNode, String contractId, YamlContract yamlContract, Collection<Contract> sccContracts) {

        yamlContract.request = new YamlContract.Request();
        yamlContract.request.method = StringUtils.upperCase(operationKey, Locale.US);

        this.iterateArray(operationNode.path("x-contracts"), (operationContractNode) -> {

            final var operationContractId = operationContractNode.path("contractId").asText(null);
            if (StringUtils.equals(operationContractId, contractId)) {
                this.addOperationRequestContract(operationContractNode, yamlContract);
            }
        });

        this.iterateArray(operationNode.path("parameters"), (paramNode) -> {
            this.iterateArray(paramNode.path("x-contracts"), (paramContractNode) -> {

                final var contractParamContractId = paramContractNode.path("contractId").asText(null);
                if (StringUtils.equals(contractParamContractId, contractId)) {
                    this.addOperationParameterContract(paramNode, paramContractNode, yamlContract);
                }
            });
        });

        this.iterateArray(operationNode.path("requestBody").path("x-contracts"), (requestBodyContractNode) -> {

            final var requestBodyContractId = requestBodyContractNode.path("contractId").asText(null);
            if (StringUtils.equals(requestBodyContractId, contractId)) {
                this.addRequestBodyContract(requestBodyContractNode, yamlContract);
            }
        });

        this.iterateFields(operationNode.path("responses"), (responseCode, responseNode) -> {
            this.iterateArray(responseNode.path("x-contracts"), (responseContractNode) -> {

                final var responseContractId = responseContractNode.path("contractId").asText(null);
                if (StringUtils.equals(responseContractId, contractId)) {
                    this.addResponsesContract(responseContractNode, responseCode, yamlContract);
                }
            });
        });

        if (StringUtils.isBlank(yamlContract.request.url)) {
            yamlContract.request.url = pathKey;
        }

        if (yamlContract.response == null) {
            log.warn("Warning: Response Object is null. Verify Response Object on contract and for proper contract Ids");
            yamlContract.response = new YamlContract.Response();
        }

        Iterables.addAll(sccContracts, this.convertYamlContractToContract(yamlContract));
    }

    static boolean checkServiceEnabled(String serviceName) {

        //if not set on contract or sys env, return true
        if (StringUtils.isBlank(serviceName)) {
            log.debug("Service Name Not Set on Contract, returning true");
            return true;
        }

        final var splitValues = StringUtils.split(System.getProperty(SERVICE_NAME_KEY), ',');
        if (ArrayUtils.isEmpty(splitValues)) {
            log.debug("System Property - " + SERVICE_NAME_KEY + " - Not set, returning true ");
            return true;
        }

        for (var i = 0; i < splitValues.length; i++) {
            splitValues[i] = StringUtils.trim(splitValues[i]);
        }

        if (splitValues.length == 0) {
            log.debug("System Property - " + SERVICE_NAME_KEY + " - Not set, returning true ");
            return true;
        }

        return ArrayUtils.contains(splitValues, serviceName);
    }
}
