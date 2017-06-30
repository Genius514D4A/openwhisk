/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package system.basic

import java.io.File

import scala.collection.JavaConversions.asScalaBuffer

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils.SUCCESS_EXIT
import common.WhiskProperties
import common.Wsk
import common.WskProps
import common.WskTestHelpers

@RunWith(classOf[JUnitRunner])
class WskSdkTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk

    behavior of "Wsk SDK"

    it should "download docker action sdk" in {
        val dir = File.createTempFile("wskinstall", ".tmp")
        dir.delete()
        dir.mkdir() should be(true)
        try {
            wsk.cli(wskprops.overrides ++ Seq("sdk", "install", "docker"), workingDir = dir).
                stdout should include("The docker skeleton is now installed at the current directory.")

            val sdk = new File(dir, "dockerSkeleton")
            sdk.exists() should be(true)
            sdk.isDirectory() should be(true)

            val dockerfile = new File(sdk, "Dockerfile")
            dockerfile.exists() should be(true)
            dockerfile.isFile() should be(true)
            val lines = FileUtils.readLines(dockerfile)
            // confirm that the image is correct
            lines.get(1) shouldBe "FROM openwhisk/dockerskeleton"

            val buildAndPushFile = new File(sdk, "buildAndPush.sh")
            buildAndPushFile.canExecute() should be(true)

            // confirm there is no other divergence from the base dockerfile
            val originalDockerfile = WhiskProperties.getFileRelativeToWhiskHome("sdk/docker/Dockerfile")
            val originalLines = FileUtils.readLines(originalDockerfile)
            lines.get(0) shouldBe originalLines.get(0)
            lines.drop(2).mkString("\n") shouldBe originalLines.drop(2).mkString("\n")
        } finally {
            FileUtils.deleteDirectory(dir)
        }
    }

    it should "download iOS sdk" in {
        val dir = File.createTempFile("wskinstall", ".tmp")
        dir.delete()
        dir.mkdir() should be(true)

        wsk.cli(wskprops.overrides ++ Seq("sdk", "install", "iOS"), workingDir = dir).
            stdout should include("Downloaded OpenWhisk iOS starter app. Unzip OpenWhiskIOSStarterApp.zip and open the project in Xcode.")

        val sdk = new File(dir, "OpenWhiskIOSStarterApp.zip")
        sdk.exists() should be(true)
        sdk.isFile() should be(true)
        FileUtils.sizeOf(sdk) should be > 20000L
        FileUtils.deleteDirectory(dir)
    }


    it should "install the bash auto-completion bash script using --bashrc flag" in {
        val auth: Seq[String] = Seq("--auth", wskprops.authKey)
        val msg = "bash completion for wsk"    // Subject to change, dependent on Cobra script
        // Doesn't actually install, simply checking if the command is printing correctly to STDOUT
        val stdout = wsk.cli(Seq("sdk", "install", "bashauto", "--bashrc") ++ wskprops.overrides ++ auth, expectedExitCode = SUCCESS_EXIT).stdout

        stdout should include(msg)
    }

}
