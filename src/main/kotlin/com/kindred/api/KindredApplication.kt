package com.kindred.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling // presence refresh (PresenceTracker)
class KindredApplication

fun main(args: Array<String>) {
    runApplication<KindredApplication>(*args)
}
