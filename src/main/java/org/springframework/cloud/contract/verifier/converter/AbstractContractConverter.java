package org.springframework.cloud.contract.verifier.converter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class AbstractContractConverter<T> implements ContractConverter<T> {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final YamlToContracts yamlToContracts = new YamlToContracts();

    @Override
    public T convertTo(Collection<Contract> contract) {
        throw new RuntimeException("Not Implemented");
    }

    protected abstract boolean isAcceptedRootJsonNode(JsonNode node);

    protected List<JsonNode> readNodes(File file) {

        try {
            final var yamlFactory = YAMLFactory.builder()
                    .build();

            final var yamlParser = yamlFactory.createParser(file);
            final var objectMapper = new ObjectMapper();

            return objectMapper.readValues(yamlParser, new TypeReference<JsonNode>() {
            }).readAll();
        } catch (IOException ex) {
            throw new RuntimeException("Could not read file '" + file + "'", ex);
        }
    }

    protected static class Entry {

        public final JsonNode node;
        public final String name;
        public final Integer index;

        Entry(JsonNode node, String name, Integer index) {
            this.node = node;
            this.name = name;
            this.index = index;
        }
    }

    protected interface Traverser extends Function<Entry[], Boolean> {
    }

    protected void traverse(JsonNode node, Traverser callback) {

        final var path = new Entry[]{new Entry(node, null, null)};
        this.traverse(path, callback);
    }

    protected boolean traverse(Entry[] path, Traverser callback) {

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

    protected void iterateFields(JsonNode node, BiConsumer<String, JsonNode> consumer) {

        final var it = node.fields();
        while (it.hasNext()) {
            final var e = it.next();
            consumer.accept(e.getKey(), e.getValue());
        }
    }

    protected void iterateArray(JsonNode node, Consumer<JsonNode> consumer) {

        for (var i = 0; i < node.size(); i++) {

            final var indexedNode = node.get(i);
            if (indexedNode == null) {
                throw new IllegalArgumentException("Called iterateArray with a probably object node: " + node);
            }

            consumer.accept(indexedNode);
        }
    }

    protected void addOperationRequestContract(JsonNode operationContractNode, YamlContract yamlContract) {

        yamlContract.request.url = operationContractNode.path("contractPath").asText(null);

        this.iterateArray(operationContractNode.path("request").path("queryParameters"), (queryParameterNode) -> {

            yamlContract.request.queryParameters.put(queryParameterNode.path("key").asText(null), queryParameterNode.path("value").asText(null));

            this.iterateArray(queryParameterNode.path("matchers"), (matcherNode) -> {

                final var queryParameterMatcher = new YamlContract.QueryParameterMatcher();
                queryParameterMatcher.key = queryParameterNode.path("name").asText(null);
                queryParameterMatcher.value = matcherNode.get("value").asText(null);
                queryParameterMatcher.type = getMatchingTypeFromString(matcherNode.path("type").asText(null));
                yamlContract.request.matchers.queryParameters.add(queryParameterMatcher);
            });
        });
    }

    protected void addResponsesContract(JsonNode responseContractNode, String responseCode, YamlContract yamlContract) {

        yamlContract.response = new YamlContract.Response();
        yamlContract.response.status = NumberUtils.toInt(responseCode.replaceAll("[^a-zA-Z0-9 ]+", ""), -1);

        yamlContract.response.body = responseContractNode.get("body"); // TODO: Convert to something expected. This is most likely wrong!
        yamlContract.response.bodyFromFile = responseContractNode.path("bodyFromFile").asText(null);
        yamlContract.response.bodyFromFileAsBytes = responseContractNode.path("bodyFromFileAsBytes").asText(null);


        this.addToMapFromObjectNode(yamlContract.response.headers, responseContractNode.path("headers"));
        this.addTestMatchersFromMatchersNode(yamlContract.response.matchers, responseContractNode.path("matchers"));

        yamlContract.response.async = responseContractNode.path("async").asBoolean(false);
        yamlContract.response.fixedDelayMilliseconds = responseContractNode.path("fixedDelayMilliseconds").asInt(0);
    }

    protected void addRequestBodyContract(JsonNode requestBodyContractNode, YamlContract yamlContract) {

        this.addToMapFromObjectNode(yamlContract.request.headers, requestBodyContractNode.path("headers"));
        this.addToMapFromObjectNode(yamlContract.request.cookies, requestBodyContractNode.path("cookies"));

        yamlContract.request.body = requestBodyContractNode.get("body");
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

    protected void addTestMatchersFromMatchersNode(YamlContract.TestMatchers testMatchers, JsonNode matchersNode) {

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

    protected void addToMapFromObjectNode(Map<String, Object> headers, JsonNode headersNode) {
        this.iterateFields(headersNode, (headerKey, headerNode) -> {
            headers.put(headerKey, this.getJavaObjectValue(headerNode));
        });
    }

    protected void addStubMatchersFromMatchersNode(YamlContract.StubMatchers stubMatchers, JsonNode matchersNode) {
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

    protected Iterable<Contract> convertYamlContractToContract(YamlContract yamlContract) {

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

    protected YamlContract readYamlContract(JsonNode node) {

        final var yamlContract = new YamlContract();
        yamlContract.name = node.path("name").asText(null);
        yamlContract.description = node.path("description").asText(null);
        yamlContract.priority = node.path("priority").asInt(0);
        yamlContract.label = node.path("label").asText(null);
        yamlContract.ignored = node.path("ignored").asBoolean(false);

        return yamlContract;
    }

    protected Integer getAsNumberOrNullOrZero(JsonNode node) {

        if (node.isNull() || node.isMissingNode()) {
            return null;
        }

        return node.asInt(0);
    }

    protected Serializable getJavaObjectValue(JsonNode node) {

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

    protected YamlContract.ValueMatcher newMultipartValueMatcher(JsonNode multipartNamedNode, String name) {

        if (StringUtils.isNotEmpty(multipartNamedNode.at(name).path("regex").asText(null))) {

            final var matcher = new YamlContract.ValueMatcher();
            matcher.regex = multipartNamedNode.at(name).path("regex").asText(null);
            matcher.predefined = getPredefinedRegexFromString(multipartNamedNode.at(name).path("predefined").asText());

            return matcher;
        }

        return null;
    }

    protected void addOperationParameterContract(JsonNode paramNode, JsonNode paramContractNode, YamlContract yamlContract) {

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
}
