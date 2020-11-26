package org.springframework.cloud.contract.verifier.converter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;

public class OpenContractConverterTest {

    @Test
    public void testAsyncApi() throws Exception {

        final var openContractConverter = new OpenContractConverter();
        final var yamlContractConverter = new YamlContractConverter();

        final var openFile = new File(OpenApiContactConverterTest.class.getResource("/asyncapi/asyncapi.yml").toURI());
        Assertions.assertTrue(openContractConverter.isAccepted(openFile), "The file contains x-contracts and should have been accepted");

        final var cloudContracts = openContractConverter.convertFrom(openFile);

        Assertions.assertNotNull(cloudContracts);
        Assertions.assertEquals(4, cloudContracts.size());
        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getDescription().equals("A longer description")));
        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Send Message As A")));
        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Send Message As B")));
        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Receive Message As A")));
        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Receive Message As B")));

        final var contractFile = new File(OpenApiContactConverterTest.class.getResource("/yml/contract_asyncapi.yml").toURI());
        final var oa3Contracts = yamlContractConverter.convertFrom(contractFile);

        final var cloudContract = cloudContracts.stream().filter(c -> c.getName().equals("Should Send Message As A")).findFirst().orElseThrow();
        final var oa3Contract = oa3Contracts.stream().filter(c -> c.getName().equals("Should Send Message As A")).findFirst().orElseThrow();

        Assertions.assertEquals(oa3Contract, cloudContract);
    }

    @Test
    public void testGraphQl() throws Exception {

        final var ourConverter = new GraphQLContractConverter();
        final var yamlContractConverter = new YamlContractConverter();

        final var openFile = new File(OpenApiContactConverterTest.class.getResource("/graphql/bookstore.graphqls").toURI());
        Assertions.assertTrue(ourConverter.isAccepted(openFile), "The file contains x-contracts and should have been accepted");

        final var cloudContracts = ourConverter.convertFrom(openFile);

        Assertions.assertNotNull(cloudContracts);
        Assertions.assertEquals(1, cloudContracts.size());

        // TODO: Check that the request and response are proper! It needs to automatically fix the request and response body to follow the GraphQL standard!

//        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getDescription().equals("A longer description")));
//        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Send Message As A")));
//        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Send Message As B")));
//        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Receive Message As A")));
//        Assertions.assertTrue(cloudContracts.stream().anyMatch(c -> c.getName().equals("Should Receive Message As B")));
//
//        final var contractFile = new File(OpenApiContactConverterTest.class.getResource("/yml/contract_asyncapi.yml").toURI());
//        final var oa3Contracts = yamlContractConverter.convertFrom(contractFile);
//
//        final var cloudContract = cloudContracts.stream().filter(c -> c.getName().equals("Should Send Message As A")).findFirst().orElseThrow();
//        final var oa3Contract = oa3Contracts.stream().filter(c -> c.getName().equals("Should Send Message As A")).findFirst().orElseThrow();
//
//        Assertions.assertEquals(oa3Contract, cloudContract);
    }
}