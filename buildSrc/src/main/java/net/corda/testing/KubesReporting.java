/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.corda.testing;

import org.apache.commons.compress.utils.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.UnionFileCollection;
import org.gradle.api.internal.tasks.testing.junit.result.AggregateTestResultsProvider;
import org.gradle.api.internal.tasks.testing.junit.result.BinaryResultBackedTestResultsProvider;
import org.gradle.api.internal.tasks.testing.junit.result.TestResultsProvider;
import org.gradle.api.internal.tasks.testing.report.DefaultTestReport;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.logging.ConsoleRenderer;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static org.gradle.internal.concurrent.CompositeStoppable.stoppable;
import static org.gradle.util.CollectionUtils.collect;

/**
 * Shameful copy of org.gradle.api.tasks.testing.TestReport - modified to handle results from k8s testing.
 * see https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.TestReport.html
 */
public class KubesReporting extends DefaultTask {

    private File destinationDir = new File(getProject().getBuildDir(), "test-reporting");
    private List<KubePodResult> podResults = new ArrayList<>();
    private boolean shouldPrintOutput = true;

    private final List<Object> results = new ArrayList<>();

    public KubesReporting() {
        //force this task to always run, as it's responsible for parsing exit codes
        getOutputs().upToDateWhen(t -> false);
    }

    @Inject
    protected BuildOperationExecutor getBuildOperationExecutor() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the directory to write the HTML report to.
     */
    @OutputDirectory
    public File getDestinationDir() {
        return destinationDir;
    }

    /**
     * Sets the directory to write the HTML report to.
     */
    public void setDestinationDir(File destinationDir) {
        this.destinationDir = destinationDir;
    }

    public void setPodResults(List<KubePodResult> podResults) {
        this.podResults = podResults;
    }

    public void setShouldPrintOutput(boolean shouldPrintOutput) {
        this.shouldPrintOutput = shouldPrintOutput;
    }

    /**
     * Returns the set of binary test results to include in the report.
     */
    public FileCollection getTestResultDirs() {
        UnionFileCollection dirs = new UnionFileCollection();
        results.forEach(result -> addTo(result, dirs));
        return dirs;
    }

    private void addTo(Object result, UnionFileCollection dirs) {
        if (result instanceof Iterable<?>) {
            ((Iterable<?>) result).forEach(item -> addTo(item, dirs));
        } else {
            dirs.addToUnion(filesToAdd(result));
        }
    }

    private ConfigurableFileCollection filesToAdd(Object result) {
        if (result instanceof Test) {
            File binResultsDir = ((Test) result).getBinResultsDir();
            return getProject().files(binResultsDir).builtBy(result);
        }

        return getProject().files(result);
    }

    /**
     * Sets the binary test results to use to include in the report. Each entry must point to a binary test results directory generated by a {@link Test}
     * task.
     */
    public void setTestResultDirs(Iterable<File> testResultDirs) {
        results.clear();
        reportOn(testResultDirs);
    }

    /**
     * Adds some results to include in the report.
     *
     * <p>This method accepts any parameter of the given types:
     *
     * <ul>
     *
     * <li>A {@link Test} task instance. The results from the test task are included in the report. The test task is automatically added
     * as a dependency of this task.</li>
     *
     * <li>Anything that can be converted to a set of {@link File} instances as per {@link org.gradle.api.Project#files(Object...)}. These must
     * point to the binary test results directory generated by a {@link Test} task instance.</li>
     *
     * <li>An {@link Iterable}. The contents of the iterable are converted recursively.</li>
     *
     * </ul>
     *
     * @param results The result objects.
     */
    public void reportOn(Object... results) {
        this.results.addAll(Arrays.asList(results));
    }

    @TaskAction
    void generateReport() {
        TestResultsProvider resultsProvider = createAggregateProvider();
        try {
            tryGenerateReport(resultsProvider);
        } finally {
            stoppable(resultsProvider).stop();
        }
    }

    private void tryGenerateReport(TestResultsProvider resultsProvider) {
        if (!resultsProvider.isHasResults()) {
            getLogger().info("{} - no binary test results found in dirs: {}.", getPath(), getTestResultDirs().getFiles());
            setDidWork(false);
            return;
        }

        DefaultTestReport testReport = new DefaultTestReport(getBuildOperationExecutor());
        testReport.generateReport(resultsProvider, getDestinationDir());

        checkReturnCodes();
    }

    private void checkReturnCodes() {
        List<KubePodResult> containersWithNonZeroReturnCodes = podResults.stream()
                .filter(result -> result.getResultCode() != 0)
                .collect(Collectors.toList());

        if (containersWithNonZeroReturnCodes.isEmpty()) {
            return;
        }

        if (shouldPrintOutput){
            containersWithNonZeroReturnCodes.forEach(this::reportContainerOutput);
        }

        String reportUrl = new ConsoleRenderer().asClickableFileUrl(new File(destinationDir, "index.html"));
        throw new GradleException("remote build failed, check test report at " + reportUrl);
    }

    private void reportContainerOutput(KubePodResult podResult) {
        try {
            System.out.println("\n##### CONTAINER OUTPUT START #####");
            IOUtils.copy(new FileInputStream(podResult.getOutput()), System.out);
            System.out.println("##### CONTAINER OUTPUT END #####\n");
        } catch (IOException ignored) {
        }
    }

    public TestResultsProvider createAggregateProvider() {
        List<TestResultsProvider> resultsProviders = new LinkedList<TestResultsProvider>();
        try {
            return tryCreateAggregateProvider(resultsProviders);
        } catch (RuntimeException e) {
            stoppable(resultsProviders).stop();
            throw e;
        }
    }

    @NotNull
    private TestResultsProvider tryCreateAggregateProvider(List<TestResultsProvider> resultsProviders) {
        FileCollection resultDirs = getTestResultDirs();

        if (resultDirs.getFiles().size() == 1) {
            return new BinaryResultBackedTestResultsProvider(resultDirs.getSingleFile());
        }

        return new AggregateTestResultsProvider(
                collect(resultDirs, resultsProviders, BinaryResultBackedTestResultsProvider::new));
    }
}
