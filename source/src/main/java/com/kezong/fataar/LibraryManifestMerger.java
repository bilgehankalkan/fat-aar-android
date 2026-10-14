package com.kezong.fataar;

import com.android.build.gradle.internal.LoggerWrapper;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.ManifestProvider;
import com.android.manifmerger.ManifestSystemProperty;
import com.android.manifmerger.MergingReport;
import com.android.utils.ILogger;

import org.apache.tools.ant.BuildException;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ManifestMerger for Library
 */
public class LibraryManifestMerger extends DefaultTask {

    @Override
    public boolean isCompatibleWithConfigurationCache() {
        return false;
    }

    private String mGradlePluginVersion;

    private String mGradleVersion;

    private File mMainManifestFile;

    private List<File> mSecondaryManifestFiles;

    private File mOutputFile;

    public void setGradlePluginVersion(String gradlePluginVersion) {
        mGradlePluginVersion = gradlePluginVersion;
    }

    public void setGradleVersion(String gradleVersion) {
        mGradleVersion = gradleVersion;
    }

    protected void doTaskAction() {
        try {
            doFullTaskAction();
        } catch (Exception e) {
            System.out.println("Gradle Plugin Version:" + mGradlePluginVersion);
            System.out.println("Gradle Version:" + mGradleVersion);
            throw new RuntimeException(e.getMessage());
        }
    }

    @TaskAction
    protected void doFullTaskAction() throws ManifestMerger2.MergeFailureException, IOException {
        ILogger iLogger = new LoggerWrapper(getLogger());
        ManifestMerger2.Invoker mergerInvoker = ManifestMerger2.
                newMerger(createMainManifestWithoutUsesSdk(), iLogger, ManifestMerger2.MergeType.LIBRARY);
        String minSdkVersion = getMainManifestMinSdkVersion();
        if (minSdkVersion != null) {
            mergerInvoker.setOverride(
                    ManifestSystemProperty.UsesSdk.MIN_SDK_VERSION,
                    minSdkVersion
            );
        }
        List<File> secondaryManifestFiles = getSecondaryManifestFiles();
        List<ManifestProvider> manifestProviders = new ArrayList<>();
        if (secondaryManifestFiles != null) {
            for (final File file : secondaryManifestFiles) {
                if (!file.exists()) {
                    continue;
                }
                manifestProviders.add(new ManifestProvider() {
                    @Override
                    public File getManifest() {
                        return file.getAbsoluteFile();
                    }

                    @Override
                    public String getName() {
                        return file.getName();
                    }
                });
            }
        }
        mergerInvoker.addManifestProviders(manifestProviders);
        MergingReport mergingReport = mergerInvoker.merge();
        if (mergingReport.getResult().isError()) {
            getLogger().error(mergingReport.getReportString());
            mergingReport.log(iLogger);
            throw new BuildException(mergingReport.getReportString());
        }

        // fix utf-8 problem in windows
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(getOutputFile()), StandardCharsets.UTF_8)
        );
        writer.append(removeUsesSdk(mergingReport
                .getMergedDocument(MergingReport.MergedManifestKind.MERGED)));
        writer.flush();
        writer.close();
    }

    private File createMainManifestWithoutUsesSdk() throws IOException {
        String manifest = Files.readString(getMainManifestFile().toPath(), StandardCharsets.UTF_8);
        String sanitizedManifest = removeUsesSdk(manifest);
        File sanitizedFile = new File(getTemporaryDir(), "main-manifest-without-uses-sdk.xml");
        Files.writeString(sanitizedFile.toPath(), sanitizedManifest, StandardCharsets.UTF_8);
        return sanitizedFile;
    }

    private String getMainManifestMinSdkVersion() throws IOException {
        Matcher matcher = Pattern.compile(
                "<uses-sdk\\b[^>]*android:minSdkVersion=\\\"([^\\\"]+)\\\""
        ).matcher(Files.readString(getMainManifestFile().toPath(), StandardCharsets.UTF_8));
        return matcher.find() ? matcher.group(1) : null;
    }

    private String removeUsesSdk(String manifest) {
        return manifest.replaceAll(
                "(?s)\\s*<uses-sdk\\b[^>]*(?:/>|>.*?</uses-sdk>)",
                ""
        );
    }

    public File getMainManifestFile() {
        return mMainManifestFile;
    }

    public void setMainManifestFile(File mainManifestFile) {
        this.mMainManifestFile = mainManifestFile;
    }

    public List<File> getSecondaryManifestFiles() {
        return mSecondaryManifestFiles;
    }

    public void setSecondaryManifestFiles(List<File> secondaryManifestFiles) {
        this.mSecondaryManifestFiles = secondaryManifestFiles;
    }

    public File getOutputFile() {
        return mOutputFile;
    }

    public void setOutputFile(File outputFile) {
        this.mOutputFile = outputFile;
    }
}
