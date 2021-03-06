package com.revolut.jooq

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig.newHostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.Ports.Binding.bindPort
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.command.ExecStartResultCallback
import com.github.dockerjava.core.command.PullImageResultCallback
import org.gradle.api.Action
import org.testcontainers.dockerclient.auth.AuthDelegatingDockerClientConfig
import java.io.Closeable
import java.lang.System.*
import java.util.UUID.randomUUID

class Docker(private val imageName: String,
             private val env: Map<String, Any>,
             private val portBinding: Pair<Int, Int>,
             private val readinessCommand: Array<String>,
             private val databaseHostResolver: DatabaseHostResolver,
             private val containerName: String = randomUUID().toString()) : Closeable {
    // https://github.com/docker-java/docker-java/issues/1048
    private val config: DockerClientConfig = AuthDelegatingDockerClientConfig(DefaultDockerClientConfig
            .createDefaultConfigBuilder()
            .build())
    private val docker: DockerClient = DockerClientBuilder.getInstance(config).build()

    fun runInContainer(action: Action<String>) {
        try {
            val dbHost = resolveDbHost()
            removeContainer()
            prepareDockerizedDb()
            action.execute(dbHost)
        } finally {
            removeContainer()
        }
    }

    private fun prepareDockerizedDb() {
        pullImage()
        startContainer()
        awaitContainerStart()
    }

    private fun pullImage() {
        val callback = PullImageResultCallback()
        docker.pullImageCmd(imageName).exec(callback)
        callback.awaitCompletion()
    }

    private fun startContainer() {
        val dbPort = ExposedPort.tcp(portBinding.first)
        docker.createContainerCmd(imageName)
                .withName(containerName)
                .withEnv(env.map { "${it.key}=${it.value}" })
                .withExposedPorts(dbPort)
                .withHostConfig(newHostConfig().withPortBindings(Ports(dbPort, bindPort(portBinding.second))))
                .exec()
        docker.startContainerCmd(containerName).exec()
    }

    private fun awaitContainerStart() {
        val execCreate = docker.execCreateCmd(containerName)
                .withCmd(*readinessCommand)
                .withAttachStdout(true)
                .exec()
        docker.execStartCmd(execCreate.id)
                .withTty(true)
                .withStdIn(`in`)
                .exec(ExecStartResultCallback(out, err))
                .awaitCompletion()
    }

    private fun resolveDbHost(): String {
        return databaseHostResolver.resolveHost(config.dockerHost)
    }

    private fun removeContainer() {
        try {
            docker.removeContainerCmd(containerName).withForce(true).exec()
        } catch (e: Exception) {
        }
    }

    override fun close() {
        docker.close()
    }
}
