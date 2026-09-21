package com.arduinobin.data

/**
 * 预置的开发板描述。fqbn 为 ardino-cli 使用的 Fully Qualified Board Name。
 * boardManagerUrl 仅第三方平台（ESP32/ESP8266/BW16）需要填写，AVR 平台已内置。
 */
data class BoardPreset(
    val id: String,
    val name: String,
    val architecture: String,
    val corePlatform: String,
    val fqbn: String,
    val boardManagerUrl: String? = null,
)

private const val AVR_IDX = "arduino:avr"
private const val ESP8266_IDX = "http://arduino.esp8266.com/stable/package_esp8266com_index.json"
private const val ESP32_IDX = "https://espressif.github.io/arduino-esp32/package_esp32_index.json"
private const val AMEBAD_IDX =
    "https://github.com/ambiot/ambd_arduino/raw/master/Arduino_package/package_realtek.com_amebad_index.json"

val DEFAULT_BOARDS = listOf(
    BoardPreset("uno", "Arduino Uno", "AVR", AVR_IDX, "arduino:avr:uno"),
    BoardPreset("nano", "Arduino Nano", "AVR", AVR_IDX, "arduino:avr:nano"),
    BoardPreset("nano_old", "Arduino Nano (ATmega328P old bootloader)", "AVR", AVR_IDX, "arduino:avr:nano:cpu=atmega328old"),
    BoardPreset("mega", "Arduino Mega 2560", "AVR", AVR_IDX, "arduino:avr:mega"),
    BoardPreset("esp8266", "NodeMCU v1.0 (ESP8266)", "ESP8266", "esp8266:esp8266", "esp8266:esp8266:nodemcuv2", ESP8266_IDX),
    BoardPreset("esp32", "ESP32 Dev Module", "ESP32", "esp32:esp32", "esp32:esp32:esp32", ESP32_IDX),
    BoardPreset("bw16", "BW16 (RTL8720DN)", "AmebaD", "realtek:AmebaD", "realtek:AmebaD:ameba_rtl8720dn", AMEBAD_IDX),
)

fun boardById(id: String): BoardPreset = DEFAULT_BOARDS.first { it.id == id }

/** 所有第三方 boards 管理器 URL（去重）。 */
val THIRD_PARTY_INDEX_URLS: List<String> =
    DEFAULT_BOARDS.mapNotNull { it.boardManagerUrl }.distinct()