Overview
Smart Zipper is a small wearable IoT device that monitors if a pants zipper is open or closed. A tiny magnet on the zipper pull is sensed by a Hall sensor placed inside the wearable. An nRF52820 sends the zipper state to an Android phone using Bluetooth Low Energy. The phone app can alert you in a discreet way if the zipper stays open for too long.

Why this project
This is a simple private solution to avoid an awkward moment. The system is local. It does not use cloud services. It does not collect data.

How it works
*A small neodymium magnet is attached to the zipper pull
*A TI DRV5032 Hall sensor is placed in the wearable near the zipper path
*When the zipper moves the magnet gets closer or farther from the sensor
*The sensor output changes and the firmware decides the zipper state
*The wearable sends one byte over BLE when the zipper state changes
*The Android app shows the status and triggers an alert only when needed

more information: https://oshwlab.com/paulo.quispe.c/nrf52820-smart-zipper
