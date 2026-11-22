package com.example.printcontrol

/**
 * Centrálna konfigurácia tvojich reálnych zariadení.
 * Sem vyplň svoje skutočné údaje z tlačiarne a Tapo účtu.
 */
object DeviceConfig {
    // Ak je true, aplikácia sa pokúsi pripojiť na reálnu tlačiareň a zásuvky.
    // Ak si to chceš najprv len vyskúšať nanečisto bez tlačiarne, prepni na false.
    const val USE_REAL_DEVICES = true

    // --- 1. BAMBU LAB NASTAVENIA ---
    // Pre priame pripojenie (doma alebo cez VPN Tailscale) zadaj IP tlačiarne.
    // Pre Bambu Cloud MQTT zadaj "us.mqtt.bambulab.com" (alebo "eu.mqtt.bambulab.com").
    const val BAMBU_HOST = "192.168.1.50"
    const val BAMBU_PORT = 8883

    // Sériové číslo tvojej tlačiarne (nájdeš v Bambu Handy alebo na displeji tlačiarne)
    const val BAMBU_SERIAL = "01P00A000000000"

    // Pri lokálnom/VPN pripojení je meno vždy "bblp" a heslo je 8-miestny Access Code z displeja.
    // Pri Bambu Cloud MQTT je meno "u_TVOJE_USER_ID" a heslo tvoj Cloud Access Token.
    const val BAMBU_USERNAME = "bblp"
    const val BAMBU_ACCESS_CODE = "12345678"

    // --- 2. KAMERA TLAČIARNE ---
    // Série P1S / P1P / A1 posielajú surový TLS JPEG stream priamo na porte 6000.
    const val BAMBU_CAMERA_PORT = 6000

    // Voliteľné: Ak používaš externú IP kameru (napr. Tapo C110 / Obico / OctoEverywhere),
    // môžeš sem vložiť priamu HTTP URL snímky (ak necháš prázdne, použije sa Bambu kamera na porte 6000).
    const val CUSTOM_CAMERA_SNAPSHOT_URL = ""

    // --- 3. TAPO SMART HOME (CLOUD / WEBHOOK) ---
    // TP-Link Cloud Token (alebo Webhook URL pre jednoduché zapnutie/vypnutie na diaľku)
    const val TAPO_CLOUD_TOKEN = "TVOJ_TPLINK_CLOUD_TOKEN"
    const val TAPO_PLUG_DEVICE_ID = "8022A1B2C3D4E5F6"
}