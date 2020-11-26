package org.springframework.cloud.contract.verifier.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Iterables;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cloud.contract.spec.Contract;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

public class AsyncApiContractConverter extends AbstractYamlContractConverter<Collection<JsonNode>> {

    @Override
    protected boolean isAcceptedRootJsonNode(JsonNode node) {
        return StringUtils.isNotBlank(node.path("asyncapi").asText(null));
    }

    @Override
    public Collection<Contract> convertFrom(File file) {

        final var sccContracts = new ArrayList<Contract>();
        for (final var node : this.readNodes(file)) {
            this.readAsyncApiContract(node, sccContracts);
        }

        return sccContracts;
    }

    @Override
    public Collection<JsonNode> convertTo(Collection<Contract> contract) {
        return null;
    }

    private void readAsyncApiContract(JsonNode spec, ArrayList<Contract> sccContracts) {

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

                            final var messageFromNode = messageContractNode.path("messageFrom");

                            final var input = new YamlContract.Input();
                            input.messageFrom = messageFromNode.isMissingNode() ? channelKey : StringUtils.defaultIfBlank(messageFromNode.asText(null), null);
                            input.messageBody = messageContractNode.path("messageBody");
                            input.messageBodyFromFile = messageContractNode.path("messageBodyFromFile").asText(null);
                            input.messageBodyFromFileAsBytes = messageContractNode.path("messageBodyFromFileAsBytes").asText(null);
                            input.triggeredBy = messageContractNode.path("triggeredBy").asText(null);
                            input.assertThat = messageContractNode.path("assertThat").asText(null);

                            this.addToMapFromObjectNode(input.messageHeaders, messageContractNode.path("messageHeaders"));
                            this.addStubMatchersFromMatchersNode(input.matchers, messageContractNode.path("matchers"));

                            if (input.messageHeaders.containsKey("Content-Type") == false) {
                                if (StringUtils.isNotBlank(defaultContentType)) {
                                    input.messageHeaders.put("Content-Type", defaultContentType);
                                }
                            }

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

                            final var sentToNode = messageContractNode.path("sentTo");

                            final var outputMessage = new YamlContract.OutputMessage();
                            outputMessage.sentTo = sentToNode.isMissingNode() ? channelKey : StringUtils.defaultIfBlank(sentToNode.asText(null), null);
                            outputMessage.body = messageContractNode.path("body");
                            outputMessage.bodyFromFile = messageContractNode.path("bodyFromFile").asText(null);
                            outputMessage.bodyFromFileAsBytes = messageContractNode.path("bodyFromFileAsBytes").asText(null);
                            outputMessage.assertThat = messageContractNode.path("assertThat").asText(null);

                            this.addToMapFromObjectNode(outputMessage.headers, messageContractNode.path("headers"));
                            this.addTestMatchersFromMatchersNode(outputMessage.matchers, messageContractNode.path("matchers"));

                            if (outputMessage.headers.containsKey("Content-Type") == false) {
                                if (StringUtils.isNotBlank(defaultContentType)) {
                                    outputMessage.headers.put("Content-Type", defaultContentType);
                                }
                            }

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

                if (yamlContract.input != null || yamlContract.outputMessage != null) {
                    Iterables.addAll(sccContracts, this.convertYamlContractToContract(yamlContract));
                } else {
                    log.warn("Contract '" + contractId + "' did not have an input nor output. Skipped.");
                }
            });
        });
    }
}
