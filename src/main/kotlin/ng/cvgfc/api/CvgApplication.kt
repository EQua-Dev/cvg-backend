package ng.cvgfc.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CvgApplication

fun main(args: Array<String>) {
    runApplication<CvgApplication>(*args)
}
