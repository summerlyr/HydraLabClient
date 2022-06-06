# HydraLabClient
This repo is the Gradle plugin of [Hydra Lab](https://hydradevicenetwork.azurewebsites.net/portal/#/).
In order to simplify the onboarding procedure to Hydra Lab for any app, this project packaged the client util and made it an easy way for any app to leverage the cloud testing service of Hydra Lab.

## Prerequisite
### TODO

## Usage
To trigger gradle task for Hydra Lab testing, simply follow below steps:
- Step 1: go to [template](https://github.com/olivershen-wow/HydraLabClient/tree/main/template) page, apply the plugin by copying and modifying the file content:
  - build.gradle
    - To introduce dependency on this plugin, please copy all content to repository/module you would like to use the plugin in.
  - gradle.properties
    - According to the comment inline and the running type you choose for your test, you should keep all required parameters and fill in them with correct values.
- Step 2: Build your project/module to enable plugin and task
- Step 3: Run gradle task triggerDeviceLabTest
  - Use gradle command to trigger the task.
  - Override any value in gradle.properties by specify command param "-PXXX=xxx".
  - Example command: **gradle triggerDeviceLabTest -PappApkPath="D:\Test Folder\app.apk"**

## Known issue
- Cannot find file when using directory as appApkPath and testApkPath.
- Hard-coded with Azure DevOps embedded variable names, currently may not be compatible to other CI tools when fetching commit related information.