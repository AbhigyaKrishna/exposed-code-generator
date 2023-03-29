package dev.epicsquid.exposed.gradle.databases.postgresql

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

object IntegerTypes : Table("integer_types") {
    val tinyIntColumn: Column<Short> = short("tiny_int_column")
    val shortColumn: Column<Short> = short("short_column")
    val integerColumn: Column<Int> = integer("integer_column")
    val longColumn: Column<Long> = long("long_column")
}