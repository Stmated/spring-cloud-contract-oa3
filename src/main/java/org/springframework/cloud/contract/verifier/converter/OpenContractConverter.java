package org.springframework.cloud.contract.verifier.converter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.google.common.collect.Iterables;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.contract.spec.Contract;
import org.springframework.cloud.contract.spec.ContractConverter;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;


/**
 * Created by John Thompson on 5/24/18.
 */
public class OpenContractConverter implements ContractConverter<Collection<JsonNode>> {

    private static final Logger log = LoggerFactory.getLogger(OpenContractConverter.class);

    public static final OpenContractConverter INSTANCE = new OpenContractConverter();
    public static final String SERVICE_NAME_KEY = "scc.enabled.servicenames";

    private final YamlToContracts yamlToContracts = new YamlToContracts();

    @Override
    public boolean isAccepted(File file) {

        try {

            if (file.exists() == false) {
                return false;
            }

            for (final var node : this.readNodes(file)) {

                final var contractsFound = new AtomicBoolean(false);
                this.traverse(node, entries -> {

                    if ("x-contracts".equalsIgnoreCase(entries[entries.length - 1].name)) {
                        contractsFound.set(true);
                        return false;
                    }

                    return true;
                });

                if (contractsFound.get()) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            log.error("Unexpected error in reading contract file: " + e.getMessage(), e);
            return false;
        }
    }

    private List<JsonNode> readNodes(File file) throws IOException {

        final var yamlFactory = YAMLFactory.builder()
                .build();

        final var yamlParser = yamlFactory.createParser(file);
        final var objectMapper = new ObjectMapper();

        return objectMapper.readValues(yamlParser, new TypeReference<JsonNode>() {
        }).readAll();
    }

    private static class Entry {

        public final JsonNode node;
        public final String name;
        public final Integer index;

        Entry(JsonNode node, String name, Integer index) {
            this.node = node;
            this.name = name;
            this.index = index;
        }
    }

    private interface Traverser extends Function<Entry[], Boolean> {
    }

    private void traverse(JsonNode node, Traverser callback) {

        final var path = new Entry[]{new Entry(node, null, null)};
        this.traverse(path, callback);
    }

    private boolean traverse(Entry[] path, Traverser callback) {

        if (callback.apply(path) == false) {
            return false;
        }

        final var node = path[path.length - 1].node;

        if (node.isValueNode()) {
            // Do nothing?
        } else if (node.isArray()) {
            for (var i = 0; i < node.size(); i++) {
                final var newPath = ArrayUtils.addAll(path, new Entry(node.get(i), null, i));
                if (this.traverse(newPath, callback) == false) {
                    return false;
                }
            }
        } else if (node.isObject()) {

            final var it = node.fields();
            while (it.hasNext()) {
                final var e = it.next();
                final var newPath = ArrayUtils.addAll(path, new Entry(e.getValue(), e.getKey(), null));
                if (this.traverse(newPath, callback) == false) {
                    return false;
                }
            }
        }

        return true;
    }

    private void iterateFields(JsonNode node, BiConsumer<String, JsonNode> consumer) {

        final var it = node.fields();
        while (it.hasNext()) {
            final var e = it.next();
            consumer.accept(e.getKey(), e.getValue());
        }
    }

    private void iterateArray(JsonNode node, Consumer<JsonNode> consumer) {

        for (var i = 0; i < node.size(); i++) {

            final var indexedNode = node.get(i);
            if (indexedNode == null) {
                throw new IllegalArgumentException("Called iterateArray with a probably object node: " + node);
            }

            consumer.accept(indexedNode);
        }
    }

    @Override
    public Collection<Contract> convertFrom(File file) {

        final var sccContracts = new ArrayList<Contract>();

        try {

            for (final var node : this.readNodes(file)) {

                // TODO: Should go through all nodes and replace all the $ref with the real target

                sccContracts.addAll(this.convertFromDocument(node));
            }

            return sccContracts;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not convert from '" + file + "'", ex);
        }
    }

    private static final String[] OPENAPI_OPERATIONS = new String[]{"get", "put", "head", "post", "delete", "patch", "options", "trace"};

    Collection<Contract> convertFromDocument(JsonNode spec) {

        final var sccContracts = new ArrayList<Contract>();

        final var isAsyncApi = StringUtils.isNotBlank(spec.path("asyncapi").asText(null));

        this.iterateFields(spec.path("paths"), (pathKey, pathNode) -> {

            for (final var operationKey : OPENAPI_OPERATIONS) {

                final var operationNode = pathNode.path(operationKey);
                this.iterateArray(operationNode.path("x-contracts"), (contractNode) -> {

                    final var contractServiceName = contractNode.path("serviceName").asText(null);

                    if (checkServiceEnabled(contractServiceName)) {
                        this.readContract(pathKey, pathNode, operationKey, operationNode, contractNode, sccContracts);
                    } else {

                        YamlContract ignored = new YamlContract();
                        ignored.name = "Ignored Contract";
                        ignored.ignored = true;
                        ignored.request = new YamlContract.Request();
                        ignored.request.url = "/ignored";
                        ignored.request.method = "GET";
                        ignored.response = new YamlContract.Response();
                    }
                });
            }
        });

        if (isAsyncApi) {

            final var infoNode = spec.path("info");

            final var infoTitle = infoNode.path("title").asText(null);
            final var infoVersion = infoNode.path("version").asText(null);
            final var infoDescription = infoNode.path("description").asText(null);
            final var infoTermsOfService = infoNode.path("termsOfService").asText(null);

            // servers?
            // servers.url
            // servers.protocol -- can be: amqp, amqps, http, https, hms, kafka, kafka-secure, mqtt, secure-mqtt, stomp, stomps, ws, wss

            //final var serverProtocol = spec.path("servers") (key=server name, value=node) .path("protocol").asText(null);

            final var defaultContentType = spec.path("defaultContentType").asText(null);

            this.iterateFields(spec.path("channels"), (channelKey, channelNode) -> {

                // TODO: Is it meaningful to be able to share contracts between subscribe and publish? Probably YES! It needs to be worked on

                this.iterateArray(channelNode.path("x-contracts"), (contractNode) -> {

                    final var yamlContract = this.readYamlContract(contractNode);

                    final var contractId = contractNode.path("contractId").asText(null);

                    final var subscribeNode = channelNode.path("subscribe");
                    if (subscribeNode.isMissingNode() == false) {

                        final var messageNode = subscribeNode.path("message");
                        final var messageName = messageNode.path("name").asText(null);

                        this.iterateArray(messageNode.path("x-contracts"), (messageContractNode) -> {

                            // TODO: Re-use same code as openapi, it's the same as the response code node!
                            final var messageContractId = messageContractNode.path("contractId").asText(null);

                            if (StringUtils.equals(messageContractId, contractId)) {

                                final var input = new YamlContract.Input();
                                input.messageFrom = channelKey;
                                input.messageBody = messageContractNode.path("body");
                                input.messageBodyFromFile = messageContractNode.path("messageBodyFromFile").asText(null);
                                input.messageBodyFromFileAsBytes = messageContractNode.path("messageBodyFromFileAsBytes").asText(null);
                                input.triggeredBy = messageContractNode.path("triggeredBy").asText(null);
                                input.assertThat = messageContractNode.path("assertThat").asText(null);

                                this.addToMapFromObjectNode(input.messageHeaders, messageContractNode.path("messageHeaders"));
                                this.addStubMatchersFromMatchersNode(input.matchers, messageContractNode.path("matchers"));

                                yamlContract.input = input;
                            }
                        });
                    }

                    final var publishNode = channelNode.path("publish");
                    if (publishNode.isMissingNode() == false) {

                        final var messageNode = publishNode.path("message");
                        final var messageName = messageNode.path("name").asText(null);

                        this.iterateArray(messageNode.path("x-contracts"), (messageContractNode) -> {

                            // TODO: Re-use same code as openapi, it's the same as the response code node!
                            final var messageContractId = messageContractNode.path("contractId").asText(null);

                            if (StringUtils.equals(messageContractId, contractId)) {

                                final var outputMessage = new YamlContract.OutputMessage();
                                outputMessage.sentTo = channelKey;
                                outputMessage.body = messageContractNode.path("body");
                                outputMessage.bodyFromFile = messageContractNode.path("bodyFromFile").asText(null);
                                outputMessage.bodyFromFileAsBytes = messageContractNode.path("bodyFromFileAsBytes").asText(null);
                                outputMessage.assertThat = messageContractNode.path("assertThat").asText(null);

                                this.addToMapFromObjectNode(outputMessage.headers, messageContractNode.path("headers"));
                                this.addTestMatchersFromMatchersNode(outputMessage.matchers, messageContractNode.path("matchers"));

                                yamlContract.outputMessage = outputMessage;
                            }
                        });
                    }

                    this.iterateFields(channelNode.path("bindings"), (bindingKey, bindingNode) -> {

                        // Example of "bindingKey" is "kafka", which has child values:
                        // https://github.com/asyncapi/bindings/blob/master/kafka/README.md#channel
//                        groupId:
//                          type: string
//                          enum: ['myGroupId']
//                        clientId:
//                          type: string
//                          enum: ['myClientId']
//                        bindingVersion: '0.1.0'

                        final var is = bindingNode.path("is"); // For example "queue"

                        final var queueNode = bindingNode.path("queue");
                        final var exclusive = queueNode.path("exclusive").asBoolean(false);
                    });

                    Iterables.addAll(sccContracts, this.convertYamlContractToContract(yamlContract));
                });
            });
        }

        return sccContracts;
    }

    private void readContract(String pathKey, JsonNode pathItem, String operationKey, JsonNode operationNode, JsonNode openApiContract, Collection<Contract> sccContracts) {

        // TODO: Try and automate as much as possible of this! Just move over whatever it may be. It should be built to match 1:1

        final YamlContract yamlContract = this.readYamlContract(openApiContract);

        final var contractId = openApiContract.path("contractId").asText(null);
        final var contractPath = StringUtils.defaultIfEmpty(openApiContract.path("contractPath").asText(null), pathKey);

        yamlContract.request = new YamlContract.Request();
        yamlContract.request.url = contractPath;
        yamlContract.request.method = StringUtils.upperCase(operationKey, Locale.US);

        this.iterateArray(openApiContract.path("request").path("queryParameters"), (queryParameterNode) -> {

            yamlContract.request.queryParameters.put(queryParameterNode.path("key").asText(null), queryParameterNode.path("value").asText(null));

            this.iterateArray(queryParameterNode.path("matchers"), (matcherNode) -> {

                YamlContract.QueryParameterMatcher queryParameterMatcher = new YamlContract.QueryParameterMatcher();
                queryParameterMatcher.key = queryParameterNode.path("name").asText(null);
                queryParameterMatcher.value = matcherNode.get("value").asText(null);
                queryParameterMatcher.type = getMatchingTypeFromString(matcherNode.path("type").asText(null));
                yamlContract.request.matchers.queryParameters.add(queryParameterMatcher);
            });
        });

        this.iterateArray(operationNode.path("parameters"), (paramNode) -> {
            this.iterateArray(paramNode.path("x-contracts"), (paramContractNode) -> {

                final var contractParamContractId = paramContractNode.path("contractId").asText(null);

                if (StringUtils.equals(contractParamContractId, contractId)) {
                    this.addOperationParameterContract(yamlContract, paramNode, paramContractNode);
                }
            });
        });

        this.iterateArray(operationNode.path("requestBody").path("x-contracts"), (requestBodyContractNode) -> {

            final var requestBodyContractId = requestBodyContractNode.path("contractId").asText(null);

            if (StringUtils.equals(requestBodyContractId, contractId)) {

                this.addToMapFromObjectNode(yamlContract.request.headers, requestBodyContractNode.path("headers"));
                this.addToMapFromObjectNode(yamlContract.request.cookies, requestBodyContractNode.path("cookies"));

                yamlContract.request.body = requestBodyContractNode.get("body"); // ?.body
                yamlContract.request.bodyFromFile = requestBodyContractNode.path("bodyFromFile").asText(null);
                yamlContract.request.bodyFromFileAsBytes = requestBodyContractNode.path("bodyFromFileAsBytes").asText(null);

                final var multipartNode = requestBodyContractNode.path("multipart");
                if (multipartNode.isMissingNode() == false) {

                    yamlContract.request.multipart = new YamlContract.Multipart();
                    yamlContract.request.matchers.multipart = new YamlContract.MultipartStubMatcher();

                    this.iterateFields(multipartNode.path("params"), (multipartParamKey, multipartParamNode) -> {

                        yamlContract.request.multipart.params.put(
                                multipartParamKey,
                                multipartParamNode.asText(null)
                        );
                    });

                    this.iterateArray(multipartNode.path("named"), (multipartNamedNode) -> {

                        final var named = new YamlContract.Named();
                        named.fileContent = multipartNamedNode.path("fileContent").asText(null);
                        named.fileName = multipartNamedNode.path("fileName").asText(null);
                        named.paramName = multipartNamedNode.path("paramName").asText(null);
                        yamlContract.request.multipart.named.add(named);
                    });
                }

                final var urlMatcher = requestBodyContractNode.path("matchers").path("url");
                if (urlMatcher.isMissingNode() == false) {

                    final var keyValueMatcher = new YamlContract.KeyValueMatcher();
                    keyValueMatcher.key = urlMatcher.path("key").asText(null);
                    keyValueMatcher.regex = urlMatcher.path("regex").asText(null);
                    keyValueMatcher.predefined = getPredefinedRegexFromString(urlMatcher.path("predefined").asText(null));
                    keyValueMatcher.command = urlMatcher.path("command").asText(null);
                    keyValueMatcher.regexType = getRegexTypeFromString(urlMatcher.path("regexType").asText(null));

                    yamlContract.request.matchers.url = keyValueMatcher;
                }

                this.iterateArray(requestBodyContractNode.path("matchers").path("queryParameters"), (queryParameterMatcherNode) -> {

                    final var queryParameterMatcher = new YamlContract.QueryParameterMatcher();
                    queryParameterMatcher.key = queryParameterMatcherNode.path("key").asText(null);
                    queryParameterMatcher.value = queryParameterMatcherNode.path("value").asText(null);
                    queryParameterMatcher.type = getMatchingTypeFromString(queryParameterMatcherNode.path("type").asText(null));

                    yamlContract.request.matchers.queryParameters.add(queryParameterMatcher);
                });

                this.addStubMatchersFromMatchersNode(yamlContract.request.matchers, requestBodyContractNode.path("matchers"));
            }
        });

        this.iterateFields(operationNode.path("responses"), (responseCode, responseNode) -> {
            this.iterateArray(responseNode.path("x-contracts"), (contractNode) -> {

                final var responseContractId = contractNode.path("contractId").asText(null);

                if (StringUtils.equals(responseContractId, contractId)) {

                    yamlContract.response = new YamlContract.Response();
                    yamlContract.response.status = NumberUtils.toInt(responseCode.replaceAll("[^a-zA-Z0-9 ]+", ""), -1);

                    yamlContract.response.body = contractNode.get("body"); // TODO: Convert to something expected. This is most likely wrong!
                    yamlContract.response.bodyFromFile = contractNode.path("bodyFromFile").asText(null);
                    yamlContract.response.bodyFromFileAsBytes = contractNode.path("bodyFromFileAsBytes").asText(null);


                    this.addToMapFromObjectNode(yamlContract.response.headers, contractNode.path("headers"));
                    this.addTestMatchersFromMatchersNode(yamlContract.response.matchers, contractNode.path("matchers"));

                    yamlContract.response.async = contractNode.path("async").asBoolean(false);
                    yamlContract.response.fixedDelayMilliseconds = contractNode.path("fixedDelayMilliseconds").asInt(0);
                }
            });
        });

        if (yamlContract.response == null) {
            log.warn("Warning: Response Object is null. Verify Response Object on contract and for proper contract Ids");
            yamlContract.response = new YamlContract.Response();
        }

        Iterables.addAll(sccContracts, this.convertYamlContractToContract(yamlContract));
    }

    private void addTestMatchersFromMatchersNode(YamlContract.TestMatchers testMatchers, JsonNode matchersNode) {

        this.iterateArray(matchersNode.path("body"), (matcherNode) -> {

            YamlContract.BodyTestMatcher bodyTestMatcher = new YamlContract.BodyTestMatcher();
            bodyTestMatcher.path = matcherNode.path("path").asText(null);
            bodyTestMatcher.value = matcherNode.path("value").asText(null);

            bodyTestMatcher.type = YamlContract.TestMatcherType.valueOf(matcherNode.path("type").asText(null));
            bodyTestMatcher.minOccurrence = this.getAsNumberOrNullOrZero(matcherNode.path("minOccurrence"));
            bodyTestMatcher.maxOccurrence = this.getAsNumberOrNullOrZero(matcherNode.path("maxOccurrence"));
            bodyTestMatcher.predefined = getPredefinedRegexFromString(matcherNode.path("predefined").asText(null));
            bodyTestMatcher.regexType = getRegexTypeFromString(matcherNode.path("regexType").asText(null));

            testMatchers.body.add(bodyTestMatcher);
        });

        this.iterateArray(matchersNode.path("headers"), (matcherNode) -> {

            YamlContract.TestHeaderMatcher testHeaderMatcher = new YamlContract.TestHeaderMatcher();
            testHeaderMatcher.key = matcherNode.path("key").asText(null);
            testHeaderMatcher.regex = matcherNode.path("regex").asText(null);
            testHeaderMatcher.command = matcherNode.path("command").asText(null);
            testHeaderMatcher.predefined = getPredefinedRegexFromString(matcherNode.path("predefined").asText(null));
            testHeaderMatcher.regexType = getRegexTypeFromString(matcherNode.path("regexType").asText(null));

            testMatchers.headers.add(testHeaderMatcher);
        });

        this.iterateArray(matchersNode.path("cookies"), (matcherNode) -> {

            YamlContract.TestCookieMatcher testCookieMatcher = new YamlContract.TestCookieMatcher();
            testCookieMatcher.key = matcherNode.path("key").asText(null);
            testCookieMatcher.regex = matcherNode.path("regex").asText(null);
            testCookieMatcher.command = matcherNode.path("command").asText(null);
            testCookieMatcher.predefined = getPredefinedRegexFromString(matcherNode.path("predefined").asText(null));
            testCookieMatcher.regexType = getRegexTypeFromString(matcherNode.path("regexType").asText(null));

            testMatchers.cookies.add(testCookieMatcher);
        });
    }

    private void addToMapFromObjectNode(Map<String, Object> headers, JsonNode headersNode) {
        this.iterateFields(headersNode, (headerKey, headerNode) -> {
            headers.put(headerKey, this.getJavaObjectValue(headerNode));
        });
    }

    private void addStubMatchersFromMatchersNode(YamlContract.StubMatchers stubMatchers, JsonNode matchersNode) {
        this.iterateArray(matchersNode.path("body"), (bodyMatcherNode) -> {

            final var bodyStubMatcher = new YamlContract.BodyStubMatcher();
            bodyStubMatcher.path = bodyMatcherNode.path("path").asText(null);
            bodyStubMatcher.value = bodyMatcherNode.path("value").asText(null);
            bodyStubMatcher.type = OpenContractConverter.getMatchingStubTypeFromString(bodyMatcherNode.path("type").asText(null));
            bodyStubMatcher.predefined = getPredefinedRegexFromString(bodyMatcherNode.path("predefined").asText(null));
            bodyStubMatcher.minOccurrence = this.getAsNumberOrNullOrZero(bodyMatcherNode.path("minOccurrence"));
            bodyStubMatcher.maxOccurrence = this.getAsNumberOrNullOrZero(bodyMatcherNode.path("maxOccurrence"));
            bodyStubMatcher.regexType = getRegexTypeFromString(bodyMatcherNode.path("regexType").asText(null));

            stubMatchers.body.add(bodyStubMatcher);
        });

        this.iterateArray(matchersNode.path("headers"), (matcherNode) -> {
            stubMatchers.headers.add(buildKeyValueMatcher(matcherNode));
        });

        this.iterateArray(matchersNode.path("cookies"), (matcherNode) -> {
            stubMatchers.cookies.add(buildKeyValueMatcher(matcherNode));
        });

        this.iterateArray(matchersNode.path("multipart"), (matcherNode) -> {

            this.iterateArray(matcherNode.path("params"), (multipartParamNode) -> {
                stubMatchers.multipart.params.add(buildKeyValueMatcher(multipartParamNode));
            });

            this.iterateArray(matcherNode.path("named"), (multipartNamedNode) -> {

                final var stubMatcher = new YamlContract.MultipartNamedStubMatcher();
                stubMatcher.paramName = multipartNamedNode.path("paramName").asText(null);
                stubMatcher.fileName = newMultipartValueMatcher(multipartNamedNode, "fileName");
                stubMatcher.fileContent = newMultipartValueMatcher(multipartNamedNode, "fileContent");
                stubMatcher.contentType = newMultipartValueMatcher(multipartNamedNode, "contentType");

                stubMatchers.multipart.named.add(stubMatcher);
            });
        });
    }

    private Iterable<Contract> convertYamlContractToContract(YamlContract yamlContract) {

        File tempFile = null;

        try {

            tempFile = File.createTempFile("sccoa3", ".yml");

            final var mapper = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.SPLIT_LINES).enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE));
            mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
            mapper.writeValue(tempFile, yamlContract);

            log.debug(tempFile.getAbsolutePath());

            return yamlToContracts.convertFrom(tempFile);
        } catch (Exception ex) {
            throw new RuntimeException("Could not convert from created YamlContract into a Contract", ex);
        } finally {
            if (tempFile != null) {
                try {

                    tempFile.deleteOnExit();
                    FileUtils.forceDelete(tempFile);
                } catch (Exception ex) {
                    log.error("Could not delete '" + tempFile + "'", ex);
                }
            }
        }
    }

    private YamlContract readYamlContract(JsonNode node) {

        final var yamlContract = new YamlContract();
        yamlContract.name = node.path("name").asText(null);
        yamlContract.description = node.path("description").asText(null);
        yamlContract.priority = node.path("priority").asInt(0);
        yamlContract.label = node.path("label").asText(null);
        yamlContract.ignored = node.path("ignored").asBoolean(false);

        return yamlContract;
    }

    private Integer getAsNumberOrNullOrZero(JsonNode node) {

        if (node.isNull() || node.isMissingNode()) {
            return null;
        }

        return node.asInt(0);
    }

    private Serializable getJavaObjectValue(JsonNode node) {

        if (node.isMissingNode() || node.isNull()) {
            return null;
        }

        if (node.isNumber()) {
            return node.asInt(0);
        }

        if (node.isBoolean()) {
            return node.asBoolean(false);
        }

        return node.asText(null);
    }

    private YamlContract.ValueMatcher newMultipartValueMatcher(JsonNode multipartNamedNode, String name) {

        if (StringUtils.isNotEmpty(multipartNamedNode.at(name).path("regex").asText(null))) {

            final var matcher = new YamlContract.ValueMatcher();
            matcher.regex = multipartNamedNode.at(name).path("regex").asText(null);
            matcher.predefined = getPredefinedRegexFromString(multipartNamedNode.at(name).path("predefined").asText());

            return matcher;
        }

        return null;
    }

    private void addOperationParameterContract(YamlContract yamlContract, JsonNode paramNode, JsonNode paramContractNode) {

        final var paramIn = paramNode.path("in").asText(null);
        final var paramName = paramNode.path("name").asText(null);
        final var paramValue = this.getJavaObjectValue(paramContractNode.path("value"));

        switch (paramIn) {
            case "path":
            case "query":

                yamlContract.request.queryParameters.put(paramName, paramValue);

                this.iterateArray(paramContractNode.path("matchers"), (contractParamMatcherNode) -> {

                    final var queryParameterMatcher = new YamlContract.QueryParameterMatcher();
                    queryParameterMatcher.key = paramName;
                    queryParameterMatcher.value = contractParamMatcherNode.path("value").asText(null);
                    queryParameterMatcher.type = getMatchingTypeFromString(contractParamMatcherNode.path("type").asText(null));
                    yamlContract.request.matchers.queryParameters.add(queryParameterMatcher);
                });

                break;
            case "header":

                yamlContract.request.headers.put(paramName, paramValue);

                this.iterateArray(paramContractNode.path("matchers"), (contractParamHeaderMatcherNode) -> {

                    final var headersMatcher = new YamlContract.HeadersMatcher();
                    headersMatcher.key = paramName;
                    headersMatcher.regex = contractParamHeaderMatcherNode.path("regex").asText(null);
                    headersMatcher.predefined = getPredefinedRegexFromString(contractParamHeaderMatcherNode.path("predefined").asText(null));
                    headersMatcher.command = contractParamHeaderMatcherNode.path("command").asText(null);
                    headersMatcher.regexType = getRegexTypeFromString(contractParamHeaderMatcherNode.path("regexType").asText(null));
                    yamlContract.request.matchers.headers.add(headersMatcher);
                });

                break;

            case "cookie":

                yamlContract.request.cookies.put(paramName, paramValue);

                break;
        }
    }

    static YamlContract.KeyValueMatcher buildKeyValueMatcher(JsonNode matcher) {

        YamlContract.KeyValueMatcher keyValueMatcher = new YamlContract.KeyValueMatcher();
        keyValueMatcher.key = matcher.path("key").asText(null);
        keyValueMatcher.regex = matcher.path("regex").asText(null);
        keyValueMatcher.command = matcher.path("command").asText(null);
        keyValueMatcher.predefined = getPredefinedRegexFromString(matcher.path("predefined").asText(null));
        keyValueMatcher.regexType = getRegexTypeFromString(matcher.path("regexType").asText(null));

        return keyValueMatcher;
    }

    static YamlContract.PredefinedRegex getPredefinedRegexFromString(String val) {
        if (StringUtils.isNotBlank(val)) {
            return YamlContract.PredefinedRegex.valueOf(val);
        }
        return null;
    }

    static YamlContract.RegexType getRegexTypeFromString(String val) {
        if (StringUtils.isNotBlank(val)) {
            return YamlContract.RegexType.valueOf(val);
        }
        return null;
    }

    static YamlContract.MatchingType getMatchingTypeFromString(String val) {
        if (StringUtils.isNotBlank(val)) {
            return YamlContract.MatchingType.valueOf(val);
        }
        return null;
    }

    static YamlContract.StubMatcherType getMatchingStubTypeFromString(String val) {
        if (StringUtils.isNotBlank(val)) {
            return YamlContract.StubMatcherType.valueOf(val);
        }
        return null;
    }

    static YamlContract.TestMatcherType getMatchingTestTypeFromString(String val) {
        if (StringUtils.isNotBlank(val)) {
            return YamlContract.TestMatcherType.valueOf(val);
        }
        return null;
    }

    @Override
    public Collection<JsonNode> convertTo(Collection<Contract> contract) {
        throw new RuntimeException("Not Implemented");
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
