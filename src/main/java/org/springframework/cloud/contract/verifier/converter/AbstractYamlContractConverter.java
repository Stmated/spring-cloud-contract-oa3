package org.springframework.cloud.contract.verifier.converter;

import com.github.jknack.handlebars.internal.lang3.StringUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractYamlContractConverter<T> extends AbstractContractConverter<T> {

    @Override
    public boolean isAccepted(File file) {

        try {

            log.info("Checking if '" + file.getAbsolutePath() + "' is accepted");

            if (file.exists() == false) {
                return false;
            }

            if (StringUtils.equalsAnyIgnoreCase(FilenameUtils.getExtension(file.getName()), "yml", "yaml") == false) {
                return false;
            }

            for (final var node : this.readNodes(file)) {

                if (this.isAcceptedRootJsonNode(node)) {

                    final var contractsFound = new AtomicBoolean(false);
                    this.traverse(node, entries -> {

                        if ("x-contracts".equalsIgnoreCase(entries[entries.length - 1].name)) {
                            contractsFound.set(true);
                            return false;
                        }

                        return true;
                    });

                    if (contractsFound.get()) {

                        log.info("File '" + file.getAbsolutePath() + "' is accepted");
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            log.error("Unexpected error in reading contract file: " + e.getMessage(), e);
            return false;
        }
    }
}
