package com.microsoft.hydralab.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import okhttp3.*;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HydraLabClientUtils {
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final int minWaitSec = 15;

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Date.class, new TypeAdapter<Date>() {
                @Override
                public void write(JsonWriter out, Date value) throws IOException {
                    if (value == null) {
                        out.nullValue();
                    } else {
                        out.value(value.getTime());
                    }
                }

                @Override
                public Date read(JsonReader in) throws IOException {
                    if (in != null) {
                        try {
                            return new Date(in.nextLong());
                        } catch (IllegalStateException e) {
                            in.nextNull();
                            return null;
                        }
                    } else {
                        return null;
                    }
                }
            }).create();
    private static boolean sMarkedFail = false;

    public static void runTestOnDeviceWithAPK(String apkPath, String testApkPath,
                                              String buildFlavor,
                                              String testSuiteName,
                                              @Nullable String deviceIdentifier,
                                              @Nullable String reportAudience,
                                              int timeoutSec,
                                              String reportFolderPath,
                                              Map<String, String> instrumentationArgs,
                                              Map<String, String> extraArgs,
                                              @Nullable HydraLabAPIConfig apiConfig) {
        sMarkedFail = false;
        try {
            runTestInner(apkPath, testApkPath, buildFlavor, testSuiteName, deviceIdentifier, reportAudience,
                    timeoutSec, reportFolderPath, instrumentationArgs, extraArgs, apiConfig);
            markBuildSuccess();
        } catch (RuntimeException e) {
            markBuildFail();
            throw e;
        }
    }

    private static void runTestInner(String apkPath, String testApkPath, String buildFlavor, String testSuiteName, @Nullable String deviceIdentifier, @Nullable String reportAudience, int timeoutSec, String reportFolderPath, Map<String, String> instrumentationArgs, Map<String, String> extraArgs, @Nullable HydraLabAPIConfig apiConfig) {
        printlnf("##[section]RunTestOnDeviceWithAPK-> buildFlavor: %s, testSuiteName: %s, deviceIdentifier: %s, reportAudience: %s, timeoutSec: %d, reportFolderPath: %s",
                buildFlavor, testSuiteName, deviceIdentifier, reportAudience, timeoutSec, reportFolderPath);
        // Collect git info
        File commandDir = new File(".");
        String commitId = "";
        String commitCount = "";
        String commitMsg = "";
        try {
            commitId = getLatestCommitHash(commandDir);
            printlnf("Commit ID: %s", commitId);
            commitCount = getCommitCount(commandDir, commitId);
            printlnf("Commit Count: %s", commitCount);
            commitMsg = getCommitMessage(commandDir, commitId);
            printlnf("Commit Message: %s", commitMsg);
        } catch (Exception e) {
            throw new IllegalArgumentException("Get commit info failed: " + e.getMessage(), e);
        }

        File apk;
        File testApk;
        try {
            File file = new File(apkPath);
            assertTrue(file.exists(), "apk not exist", null);

            if (file.isDirectory()) {
                apk = file.listFiles()[0];
            } else {
                apk = file;
            }

            file = new File(testApkPath);
            assertTrue(file.exists(), "testApk not exist", null);
            if (file.isDirectory()) {
                testApk = file.listFiles()[0];
            } else {
                testApk = file;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("APK not found: " + e.getMessage(), e);
        }

        if (apiConfig == null) {
            apiConfig = HydraLabAPIConfig.defaultAPI();
        }

        String apkSetId = uploadAPK(apiConfig, buildFlavor, commitId, commitCount, commitMsg, apk, testApk);
        printlnf("##[section]Uploaded APK set id: %s", apkSetId);
        assertNotNull(apkSetId, "apkSetId");

        apiConfig.pipelineLink = System.getenv("SYSTEM_TEAMFOUNDATIONSERVERURI") + System.getenv("SYSTEM_TEAMPROJECT") + "/_build/results?buildId=" + System.getenv("BUILD_BUILDID");
        printlnf("##[section]Callback pipeline link is: %s", apiConfig.pipelineLink);

        JsonObject responseContent = triggerTestRun(apiConfig, apkSetId, testSuiteName, deviceIdentifier, reportAudience, timeoutSec, instrumentationArgs, extraArgs);
        int resultCode = responseContent.get("code").getAsInt();

        // retry
        int waitingRetry = 20;
        while (resultCode == 500 && waitingRetry > 0) {
            sleepIgnoreInterrupt(30);
            responseContent = triggerTestRun(apiConfig, apkSetId, testSuiteName, deviceIdentifier, reportAudience, timeoutSec, instrumentationArgs, extraArgs);
            resultCode = responseContent.get("code").getAsInt();
            waitingRetry--;
        }
        assertTrue(resultCode != 500, "All devices are busy in the lab", null);
        assertTrue(resultCode == 200, "Server returned code: " + resultCode, responseContent);

        String testTaskId = responseContent.getAsJsonObject("content").get("testTaskId").getAsString();
//        printlnf("##[section]Triggered test task id: %s, running on %d devices", testTaskId, testTask.testDevicesCount);
        printlnf("##[section]Triggered test task id: %s successful!", testTaskId);

        int sleepSecond = timeoutSec / 3;
        int totalWaitSecond = 0;
        boolean finished = false;
        TestTask runningTest = null;
        int HydraRetryTime = 0;
        while (!finished) {
            if (totalWaitSecond > timeoutSec) {
                break;
            }
            printlnf("Get test status after waiting for %d seconds", totalWaitSecond);
            runningTest = getTestStatus(apiConfig, testTaskId);
            printlnf("Current running test info: %s", runningTest.toString());
            assertNotNull(runningTest, "testTask");

            String currentStatus = runningTest.status;
            if (HydraRetryTime != runningTest.retryTime) {
                HydraRetryTime = runningTest.retryTime;
                printlnf("##[command]Retrying to run task again, waitSecond will be reset. current retryTime is : %d", HydraRetryTime);
                totalWaitSecond = 0;
                sleepSecond = timeoutSec / 3;
            }

            if (TestTask.TestStatus.WAITING.equals(currentStatus)) {
                printlnf("##[command]" + runningTest.message + " Start waiting: 30 seconds");
                sleepIgnoreInterrupt(30);
            } else {
                //printlnf("##[command]Running test on %d devices, status for now: %s", runningTest.testDevicesCount, runningTest.status);
                printlnf("##[command]Running test on %d device, status for now: %s", runningTest.testDevicesCount, currentStatus);
                assertTrue(!TestTask.TestStatus.CANCELED.equals(currentStatus), "The test task is canceled", runningTest);
                assertTrue(!TestTask.TestStatus.EXCEPTION.equals(currentStatus), "The test task is error", runningTest);
                finished = TestTask.TestStatus.FINISHED.equals(currentStatus);
                if (finished) {
                    break;
                }
                // using ##[command] as a highlight indicator
                printlnf("##[command]Start waiting: %d seconds", sleepSecond);
                sleepIgnoreInterrupt(sleepSecond);
                totalWaitSecond += sleepSecond;
                // binary wait with min boundary
                sleepSecond = Math.max(sleepSecond / 2, minWaitSec);
            }
        }

        assertTrue(finished, "Time out after waiting for " + timeoutSec + " seconds! Test id", runningTest);
        assertNotNull(runningTest, "runningTest");

        assertNotNull(runningTest.deviceTestResults, "runningTest.deviceTestResults");

        String testReportUrl = apiConfig.getTestReportUrl(runningTest.id);

        StringBuilder mdBuilder = new StringBuilder("# Device Lab Test Result Details\n\n\n");
        mdBuilder.append(String.format("### [Link to full report](%s)\n\n\n", testReportUrl));
        mdBuilder.append(String.format("### Statistic: total test case count: %s, failed: %s\n\n", runningTest.totalTestCount, runningTest.totalFailCount));
        if (runningTest.totalFailCount > 0 && runningTest.reportImagePath != null) {
            printlnf("##[warning] %d cases failed during the test", runningTest.totalFailCount);
        }

        if (runningTest.totalFailCount > 0) {
            markBuildFail();
        }

        int index = 0;

        printlnf("##vso[task.setprogress value=90;]Almost Done with testing");
        printlnf("##[section]Start going through device test results, Test overall info: %s", runningTest);

        for (DeviceTestResult deviceTestResult : runningTest.deviceTestResults) {
            if (deviceTestResult.testXmlReportBlobUrl == null) {
                continue;
            }
            printlnf(">>>>>>\n Device %s, failed cases count: %d, total cases: %d", deviceTestResult.deviceSerialNumber, deviceTestResult.failCount, deviceTestResult.totalCount);
            if (deviceTestResult.failCount > 0 || deviceTestResult.totalCount == 0) {
                if (deviceTestResult.crashStack != null && deviceTestResult.crashStack.length() > 0) {
                    printlnf("##[error]Fatal error during test on device %s, stack:\n%s", deviceTestResult.deviceSerialNumber, deviceTestResult.crashStack);
                }
                else {
                    printlnf("##[error]Fatal error during test on device %s with no stack found.", deviceTestResult.deviceSerialNumber);
                }
                markBuildFail();
            }

            String adbReportUrl = deviceTestResult.instrumentReportBlobUrl;
            printlnf("Start downloading adb log for device %s, device name %s, link: %s", deviceTestResult.deviceSerialNumber, deviceTestResult.deviceName, adbReportUrl);
            File file = new File(reportFolderPath, "ADB-" + testSuiteName + "-" + deviceTestResult.deviceSerialNumber + ".log");
            downloadToFile(adbReportUrl, file);
            if (file.exists()) {
                printlnf("Finish downloading adb log for device %s", deviceTestResult.deviceSerialNumber);
                // use the https://docs.microsoft.com/en-us/azure/devops/pipelines/scripts/logging-commands?view=azure-devops&tabs=powershell#build-commands
                // to upload the report
                printlnf("##vso[artifact.upload artifactname=testResult;]%s", file.getAbsolutePath());
            }
            else {
                printlnf("No adb log for device %s exists, skip downloading.", deviceTestResult.deviceSerialNumber);
            }


            String xmlReportUrl = deviceTestResult.testXmlReportBlobUrl;
            printlnf("Start downloading xml report for device %s, device name %s, link: %s", deviceTestResult.deviceSerialNumber, deviceTestResult.deviceName, xmlReportUrl);
            file = new File(reportFolderPath, "TEST-" + testSuiteName + "-" + deviceTestResult.deviceSerialNumber + ".xml");
            downloadToFile(xmlReportUrl, file);
            if (file.exists()) {
                printlnf("Finish downloading xml test report for device %s", deviceTestResult.deviceSerialNumber);
                // use the https://docs.microsoft.com/en-us/azure/devops/pipelines/scripts/logging-commands?view=azure-devops&tabs=powershell#build-commands
                // to upload the report
                printlnf("##vso[artifact.upload artifactname=testResult;]%s", file.getAbsolutePath());
            }
            else {
                printlnf("No xml test report for device %s exists, skip downloading.", deviceTestResult.deviceSerialNumber);
            }

            String logcatLogUrl = deviceTestResult.logcatBlobUrl;
            printlnf("Start downloading logcat log for device %s, device name %s, link: %s", deviceTestResult.deviceSerialNumber, deviceTestResult.deviceName, logcatLogUrl);
            file = new File(reportFolderPath, "logcat-" + testSuiteName + "-" + deviceTestResult.deviceSerialNumber + ".log");
            downloadToFile(logcatLogUrl, file);
            if (file.exists()) {
                printlnf("Finish downloading logcat Log for device %s", deviceTestResult.deviceSerialNumber);
                // use the https://docs.microsoft.com/en-us/azure/devops/pipelines/scripts/logging-commands?view=azure-devops&tabs=powershell#build-commands
                // to upload the report
                printlnf("##vso[artifact.upload artifactname=testResult;]%s", file.getAbsolutePath());
            }
            else {
                printlnf("No logcat log for device %s exists, skip downloading.", deviceTestResult.deviceSerialNumber);
            }

            String testGifUrl = deviceTestResult.testGifBlobUrl;
            printlnf("Start downloading test Gif for device %s, device name %s, link: %s", deviceTestResult.deviceSerialNumber, deviceTestResult.deviceName, testGifUrl);
            file = new File(reportFolderPath, "rec_" + deviceTestResult.deviceSerialNumber + ".gif");
            downloadToFile(testGifUrl, file);
            if (file.exists()) {
                printlnf("Finish downloading test Gif for device %s", deviceTestResult.deviceSerialNumber);
                printlnf("##vso[artifact.upload artifactname=testResult;]%s", file.getAbsolutePath());
            }
            else {
                printlnf("No test Gif for device %s exists, skip downloading.", deviceTestResult.deviceSerialNumber);
            }

            String deviceTestVideoUrl = apiConfig.getDeviceTestVideoUrl(deviceTestResult.id);
            printlnf("##[command]Device %s test video link: %s\n>>>>>>>>", deviceTestResult.deviceSerialNumber, deviceTestVideoUrl);
            // set this as a variable as we might need this in next task
            printlnf("##vso[task.setvariable variable=TestVideoLink%d;]%s", ++index, deviceTestVideoUrl);

            mdBuilder.append(String.format(Locale.US, "- On device %s (SN: %s), total case count: %d, failed: %d **[Video Link](%s)**\n", deviceTestResult.deviceName, deviceTestResult.deviceSerialNumber, deviceTestResult.totalCount, deviceTestResult.failCount, deviceTestVideoUrl));
        }

        printlnf("##[section]All done, overall failed cases count: %d, total count: %d, devices count: %d", runningTest.totalFailCount, runningTest.totalTestCount, runningTest.testDevicesCount);
        printlnf("##[section]Test task report link:");
        printlnf(testReportUrl);
        printlnf("##vso[task.setvariable variable=TestTaskReportLink;]%s", testReportUrl);

        File summaryMd = new File(reportFolderPath, "TestLabSummary.md");
        try (FileOutputStream fos = new FileOutputStream(summaryMd)) {
            IOUtils.write(mdBuilder.toString(), fos, StandardCharsets.UTF_8);
            printlnf("##vso[task.uploadsummary]%s", summaryMd.getAbsolutePath());
        } catch (IOException e) {
            // no need to rethrow
            e.printStackTrace();
        }
    }

    private static void markBuildFail() {
        if (sMarkedFail) {
            return;
        }
        printlnf("##vso[build.addbuildtag]FAIL");
        sMarkedFail = true;
    }

    private static void markBuildSuccess() {
        if (sMarkedFail) {
            return;
        }
        printlnf("##vso[build.addbuildtag]SUCCESS");
    }

    private static void downloadToFile(String xmlReportUrl, File file) {
        Request req = new Request.Builder().get().url(xmlReportUrl).build();
        try (Response response = client.newCall(req).execute()) {
            if (!response.isSuccessful()) {
                return;
            }
            if (response.body() == null) {
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                IOUtils.copy(response.body().byteStream(), fos);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void assertNotNull(Object notnull, String argName) {
        if (notnull == null) {
            throw new IllegalArgumentException(argName + " is null");
        }
    }

    private static void assertTrue(boolean beTrue, String msg, Object data) {
        if (!beTrue) {
            throw new IllegalStateException(msg + (data == null ? "" : ": " + data));
        }
    }

    private static void printlnf(String format, Object... args) {
        System.out.printf(format + "\n", args);
    }

    private static void sleepIgnoreInterrupt(int second) {
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(second));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static TestTask getTestStatus(HydraLabAPIConfig apiConfig, String testId) {
        Request req = new Request.Builder()
                .addHeader("Authorization", "Bearer " + apiConfig.authToken)
                .url(apiConfig.getTestStatusUrl(testId))
                .build();
        OkHttpClient clientToUse = client;
        try (Response response = clientToUse.newCall(req).execute()) {
            assertTrue(response.isSuccessful(), "getTestStatus", response);
            ResponseBody body = response.body();
            assertNotNull(body, response + ": getTestStatus ResponseBody");
            JsonObject jsonObject = GSON.fromJson(body.string(), JsonObject.class);

            int resultCode = jsonObject.get("code").getAsInt();
            assertTrue(resultCode == 200, "Server returned code: " + resultCode, jsonObject);

            return GSON.fromJson(jsonObject.getAsJsonObject("content"), TestTask.class);
        } catch (Exception e) {
            throw new RuntimeException("update APK fail: " + e.getMessage(), e);
        }
    }

    private static JsonObject triggerTestRun(HydraLabAPIConfig apiConfig, String apkSetId, String testSuiteName,
                                             @Nullable String deviceIdentifier, @Nullable String reportAudience, int timeoutSec, Map<String, String> instrumentationArgs, Map<String, String> extraArgs) {
        JsonObject jsonElement = new JsonObject();
        jsonElement.addProperty("testSuiteClass", testSuiteName);
        jsonElement.addProperty("testTimeOutSec", timeoutSec);
        jsonElement.addProperty("pkgName", apiConfig.getPkgName());
        jsonElement.addProperty("testPkgName", apiConfig.getTestPkgName());
        jsonElement.addProperty("apkSetId", apkSetId);
        jsonElement.addProperty("groupTestType", apiConfig.groupTestType);
        jsonElement.addProperty("pipelineLink", apiConfig.pipelineLink);
        jsonElement.addProperty("runningType", apiConfig.runningType);
        jsonElement.addProperty("frameworkType", apiConfig.frameworkType);

        if (reportAudience != null) {
            jsonElement.addProperty("reportAudience", reportAudience);
        }
        if (deviceIdentifier != null) {
            jsonElement.addProperty("deviceIdentifier", deviceIdentifier);
        }
        if (instrumentationArgs != null) {
            jsonElement.add("instrumentationArgs", GSON.toJsonTree(instrumentationArgs).getAsJsonObject());
        }
        if (extraArgs != null) {
            extraArgs.forEach(jsonElement::addProperty);
        }

        String content = GSON.toJson(jsonElement);
        printlnf("triggerTestRun api post body: %s", content);
        RequestBody jsonBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), GSON.toJson(jsonElement));

        Request req = new Request.Builder()
                .addHeader("Authorization", "Bearer " + apiConfig.authToken)
                .url(apiConfig.getRunTestUrl())
                .post(jsonBody).build();
        OkHttpClient clientToUse = client;
        try (Response response = clientToUse.newCall(req).execute()) {
            assertTrue(response.isSuccessful(), "triggerTestRun", response);
            ResponseBody body = response.body();
            assertNotNull(body, response + ": triggerTestRun ResponseBody");
            String string = body.string();
            printlnf("RunningTestJson: %s", string);
            JsonObject jsonObject = GSON.fromJson(string, JsonObject.class);

            return jsonObject;
        } catch (Exception e) {
            throw new RuntimeException("update APK fail: " + e.getMessage(), e);
        }
    }

    private static String uploadAPK(HydraLabAPIConfig apiConfig, String buildFlavor, String commitId, String commitCount, String commitMsg, File apk, File testApk) {
        MediaType contentType = MediaType.get("application/vnd.android.package-archive");
        Request req = new Request.Builder()
                .addHeader("Authorization", "Bearer " + apiConfig.authToken)
                .url(apiConfig.getUploadUrl())
                .post(new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("commitId", commitId)
                        .addFormDataPart("commitCount", commitCount)
                        .addFormDataPart("commitMessage", commitMsg)
                        .addFormDataPart("buildFlavor", buildFlavor)
                        .addFormDataPart("apkFile", apk.getName(), RequestBody.create(contentType, apk))
                        .addFormDataPart("testApkFile", testApk.getName(), RequestBody.create(contentType, testApk))
                        .build())
                .build();
        OkHttpClient clientToUse = client;
//        try (Response response = clientToUse.newCall(req).execute()) {
        try (Response response = clientToUse.newCall(req).execute()) {
            assertTrue(response.isSuccessful(), "uploadAPK", response);
            ResponseBody body = response.body();

            assertNotNull(body, response + ": uploadAPK ResponseBody");
            JsonObject jsonObject = GSON.fromJson(body.string(), JsonObject.class);

            int resultCode = jsonObject.get("code").getAsInt();
            assertTrue(resultCode == 200, "Server returned code: " + resultCode, jsonObject);

            return jsonObject.getAsJsonObject("content").get("id").getAsString();
        } catch (Exception e) {
            throw new RuntimeException("uploadAPK APK fail: " + e.getMessage(), e);
        }
    }

    private static String getCommitCount(File commandDir, String startCommit) throws IOException {
        Process process = Runtime.getRuntime().exec(String.format("git rev-list --first-parent --right-only --count %s..HEAD", startCommit), null, commandDir.getAbsoluteFile());
        try (InputStream inputStream = process.getInputStream()) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8).trim();
        } finally {
            process.destroy();
        }
    }

    public static String getLatestCommitHash(File commandDir) throws IOException {
        Process process = Runtime.getRuntime().exec(new String[]{"git", "log", "-1", "--pretty=format:%h"}, null, commandDir.getAbsoluteFile());
        try (InputStream inputStream = process.getInputStream()) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8).trim();
        } finally {
            process.destroy();
        }
    }

    public static String getCommitMessage(File workingDirFile, String commitId) throws IOException {
        Process process = Runtime.getRuntime().exec("git log --pretty=format:%s " + commitId + " -1", null, workingDirFile.getAbsoluteFile());
        try (InputStream inputStream = process.getInputStream()) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8).trim();
        } finally {
            process.destroy();
        }
    }

    public static class HydraLabAPIConfig {
        public String schema = "https";
        public String host = "hydradevicenetwork.azurewebsites.net";
        public String contextPath = "";
        public String authToken = "";
        public boolean onlyAuthPost = true;
        public String uploadAPKAPIPath = "/api/package/add";
        public String runTestAPIPath = "/api/test/task/run/";
        public String testStatusAPIPath = "/api/test/task/";
        public String testPortalTaskInfoPath = "/portal/index.html?redirectUrl=/info/task/";
        public String testPortalTaskDeviceVideoPath = "/portal/index.html?redirectUrl=/info/videos/";
        public String pkgName = "";
        public String testPkgName = "";
        public String groupTestType = "SINGLE";
        public String pipelineLink = "";
        public String runningType = "";
        public String frameworkType = "JUnit4";

        public static HydraLabAPIConfig defaultAPI() {
            return new HydraLabAPIConfig();
        }

        public String getPkgName() {
            return pkgName;
        }

        public String getTestPkgName() {
            return testPkgName;
        }

        public String getUploadUrl() {
            return String.format(Locale.US, "%s://%s%s%s", schema, host, contextPath, uploadAPKAPIPath);
        }

        public String getRunTestUrl() {
            return String.format(Locale.US, "%s://%s%s%s", schema, host, contextPath, runTestAPIPath);
        }

        public String getTestStatusUrl(String testTaskId) {
            return String.format(Locale.US, "%s://%s%s%s%s", schema, host, contextPath, testStatusAPIPath, testTaskId);
        }

        public String getTestReportUrl(String testTaskId) {
            return String.format(Locale.US, "%s://%s%s%s%s", schema, host, contextPath, testPortalTaskInfoPath, testTaskId);
        }

        public String getDeviceTestVideoUrl(String id) {
            return String.format(Locale.US, "%s://%s%s%s%s", schema, host, contextPath, testPortalTaskDeviceVideoPath, id);
        }

        @Override
        public String toString() {
            return "HydraLabAPIConfig Upload URL {" + getUploadUrl() + '}';
        }

        public String getTestStaticResUrl(String resPath) {
            return String.format(Locale.US, "%s://%s%s%s", schema, host, contextPath, resPath);
        }
    }

    public static class TestTask {
        public String id;
        public List<DeviceTestResult> deviceTestResults;
        public int testDevicesCount;
        public Date startDate;
        public Date endDate;
        public int totalTestCount;
        public int totalFailCount;
        public String testSuite;
        public String reportImagePath;
        public String baseUrl;
        public String status;
        public String testErrorMsg;
        public String message;
        public int retryTime;

        @Override
        public String toString() {
            return "TestTask{" +
                    "id='" + id + '\'' +
                    ", testDevicesCount=" + testDevicesCount +
                    ", startDate=" + startDate +
                    ", totalTestCount=" + totalTestCount +
                    ", baseUrl='" + baseUrl + '\'' +
                    ", status='" + status + '\'' +
                    '}';
        }

        public interface TestStatus {
            String RUNNING = "running";
            String FINISHED = "finished";
            String CANCELED = "canceled";
            String EXCEPTION = "error";
            String WAITING = "waiting";
        }
    }

    public static class DeviceTestResult {
        public String id;
        public String deviceSerialNumber;
        public String deviceName;
        public String instrumentReportPath;
        public String controlLogPath;
        public String instrumentReportBlobUrl;
        public String testXmlReportBlobUrl;
        public String logcatBlobUrl;
        public String testGifBlobUrl;

        public String crashStackId;
        public String errorInProcess;

        public String crashStack;

        public int totalCount;
        public int failCount;
        public boolean success;
        public long testStartTimeMillis;
        public long testEndTimeMillis;

        @Override
        public String toString() {
            return "{" +
                    "SN='" + deviceSerialNumber + '\'' +
                    ", totalCase:" + totalCount +
                    ", failCase:" + failCount +
                    ", success:" + success +
                    '}';
        }
    }

}
