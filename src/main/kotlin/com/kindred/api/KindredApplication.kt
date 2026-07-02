package com.kindred.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KindredApplication

fun main(args: Array<String>) {
    runApplication<KindredApplication>(*args)
}
