/*
 * The MIT License
 *
 * Copyright (c) 2016, Carlos Sanchez
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.junit.Assert.*;
import static org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil.*;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.logging.Level;

import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRuleNonLocalhost;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import hudson.model.Node;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * @author Carlos Sanchez
 * @since
 *
 */
public class KubernetesPipelineTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();
    @Rule
    public JenkinsRuleNonLocalhost r = new JenkinsRuleNonLocalhost();
    @Rule
    public LoggerRule logs = new LoggerRule().record(KubernetesCloud.class, Level.ALL);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static KubernetesCloud cloud = new KubernetesCloud("kubernetes");

    @BeforeClass
    public static void configureCloud() throws Exception {
        cloud.setServerUrl(new URL("https", miniKubeIp(), 8443, "").toExternalForm());
        cloud.setNamespace("default");
        cloud.connect();
    }

    // @Test
    // public void configRoundTrip() {
    // story.addStep(new Statement() {
    // @Override
    // public void evaluate() throws Throwable {
    // Xvnc xvnc = new Xvnc(true, false);er-1

    // CoreWrapperStep step = new CoreWrapperStep(xvnc);
    // step = new StepConfigTester(story.j).configRoundTrip(step);
    // story.j.assertEqualDataBoundBeans(xvnc, step.getDelegate());
    // }
    // });
    // }

    @Test
    public void runInPod() throws Exception {
        // do not run if minikube is not running
        assumeMiniKube();

        // Slaves running in Kubernetes (minikube) need to connect to this server, so localhost does not work
        URL url = r.getURL();
        URL nonLocalhostUrl = new URL(url.getProtocol(), InetAddress.getLocalHost().getHostAddress(), url.getPort(),
                url.getFile());
        JenkinsLocationConfiguration.get().setUrl(nonLocalhostUrl.toString());

        r.jenkins.clouds.add(cloud);

        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("" //
                + "podTemplate(label: 'mypod', containers: [\n" //
                + "        containerTemplate(name: 'jnlp', image: 'jenkinsci/jnlp-slave:2.62-alpine', args: '${computer.jnlpmac} ${computer.name}'),\n" //
                + "        containerTemplate(name: 'maven', image: 'maven:3.3.9-jdk-8-alpine', ttyEnabled: true, command: 'cat'),\n" //
                + "        containerTemplate(name: 'golang', image: 'golang:1.6.3-alpine', ttyEnabled: true, command: 'cat')\n" //
                + "    ]) {\n" //
                + "\n" //
                + "    node ('mypod') {\n" //
                + "    sh \"echo My Kubernetes Pipeline\" \n" //
                // + " stage 'Get a Maven project'\n" //
                // + " git 'https://github.com/jenkinsci/kubernetes-plugin.git'\n" //
                // + " container('maven') {\n" //
                // + " stage 'Build a Maven project'\n" //
                // + " sh 'mvn clean install'\n" //
                // + " }\n" //
                // + "\n" //
                // + " stage 'Get a Golang project'\n" //
                // + " git url: 'https://github.com/hashicorp/terraform.git'\n" //
                // + " container('golang') {\n" //
                // + " stage 'Build a Go project'\n" //
                // + " sh \"\"\"\n" //
                // + " mkdir -p /go/src/github.com/hashicorp\n" //
                // + " ln -s `pwd` /go/src/github.com/hashicorp/terraform\n" //
                // + " cd /go/src/github.com/hashicorp/terraform && make core-dev\n" //
                // + " \"\"\"\n" //
                // + " }\n" //
                + "\n" //
                + "    }\n" //
                + "}\n" //
                , true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        assertNotNull(b);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("My Kubernetes Pipeline", b);
    }

    // @Test
    public void runInPodWithRestart() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                story.j.jenkins.clouds.add(new KubernetesCloud("test"));

                story.j.jenkins.addNode(new DumbSlave("slave", "dummy", tmp.newFolder("remoteFS").getPath(), "1",
                        Node.Mode.NORMAL, "", story.j.createComputerLauncher(null), RetentionStrategy.NOOP,
                        Collections.<NodeProperty<?>> emptyList())); // TODO JENKINS-26398 clumsy
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("" //
                        + "node('slave') {\n" //
                        + "  podTemplate(label: 'mypod', containers: {" //
                        + "        'jnlp', containerTemplate(image: 'jenkinsci/jnlp-slave:alpine', args: '${computer.jnlpmac} ${computer.name}'}," //
                        + "        'maven', containerTemplate(image: 'maven:3-jdk-8', ttyEnabled: true, command: 'cat'}," //
                        + "        'golang', containerTemplate(image: 'golang:1.6', ttyEnabled: true, command: 'cat'}," //
                        + "    }) {" //
                        + "" //
                        + "    node ('mypod') {" //
                        + "        stage 'Get a Maven project'" //
                        + "        git 'https://github.com/jenkinsci/kubernetes-plugin.git'" //
                        + "        container('maven') {" //
                        + "            stage 'Build a Maven project'" //
                        + "            sh 'mvn clean install'" //
                        + "        }" //
                        + "" //
                        + "        stage 'Get a Golang project'" //
                        + "        git url: 'https://github.com/hashicorp/terraform.git'" //
                        + "        container('golang') {" //
                        + "            stage 'Build a Go project'" //
                        + "            sh \"\"\"" //
                        + "            mkdir -p /go/src/github.com/hashicorp" //
                        + "            ln -s `pwd` /go/src/github.com/hashicorp/terraform" //
                        + "            cd /go/src/github.com/hashicorp/terraform && make core-dev" //
                        + "            \"\"\"" //
                        + "        }" //
                        + "" //
                        + "    }" //
                        + "  }" //
                        + "}" //
                , true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("withDisplayAfterRestart/1", b);
            }
        });
        story.addStep(new Statement() {
            @SuppressWarnings("SleepWhileInLoop")
            @Override
            public void evaluate() throws Throwable {
                SemaphoreStep.success("withDisplayAfterRestart/1", null);
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                assertNotNull(p);
                WorkflowRun b = p.getBuildByNumber(1);
                assertNotNull(b);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                story.j.assertLogContains("DISPLAY=:", b);
                r.assertLogContains("xxx", b);
            }
        });
    }
}
