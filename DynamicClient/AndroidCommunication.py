#!/usr/bin/python

from settings import *

import socket
import threading
import time
from subprocess import call
import os
import xml.etree.ElementTree

class AndroidCommunication:
    def __init__(self, onInputCallback):
        self.socket = None
        self.socketInfoRead = None
        self.onInput = onInputCallback
        self.readThread = None

    def connectToDevice(self):
        # Wait until device is ready
        print("Looking for Android device and IntelliDroidService...")
        call(["adb", "wait-for-device", "shell", ""'while [ "$(getprop dev.bootcomplete)" != "1" ] ; do sleep 1; done'""])

        # Forward port 12348 to the device 
        call(["adb", "forward", "tcp:12348", "tcp:12348"])

        # Create a socket object
        self.socket = socket.socket()
        self.socket.connect(("127.0.0.1", 12348))

        # Create thread to receive input from device
        self.done = False
        self.readThread = threading.Thread(target = self.readSocket)
        self.readThread.daemon = True
        self.readThread.start()

        print("Connected to IntelliDroidService")

    def disconnect(self):
        # Close the socket
        self.readThread.join(3)
        self.socket.close()
        self.socket = None

    def stopAnalysis(self):
        if self.socket is not None:
            self.sendSocket("CLOSE")
            time.sleep(0.25)

    def killEmulator(self):
        call(["adb", "emu", "kill"])

    def readSocket(self):
        while True:
            data = self.socket.recv(1024)
            if data:
                self.onInput(data)

    def sendSocket(self, data):
        self.socket.send(data + "\n")

    def installApplication(self, apkFile):
        call(["adb", "install", apkFile])

    def startAnalysisForPackage(self, packageName):
        self.sendSocket("START " + packageName)
        time.sleep(0.5)

    def startActivity(self, activityName):
        call(["adb", "shell", "am", "start", "-W", "-n", activityName])

    def startService(self, serviceName):
        call(["adb", "shell", "am", "startservice", "-n", serviceName])

    def getSharedPreferenceValue(self, packageName, key):
        appDir = "/data/data/" + packageName
        appDirContents = call(["adb", "shell", "ls", appDir])

        if not "shared_prefs" in appDirContents:
            return None

        sharedPrefDir =  appDir + "/shared_prefs"
        call(["adb", "pull", sharedPrefDir, TEMP_DIR])

        sharedPreferences = {}

        for sharedPrefFileName in os.listdir(TEMP_DIR):
            sharedPrefFile = os.path.join(TEMP_DIR, sharedPrefFileName)
            xmlTree = ElementTree.parse(sharedPrefFile)
            mapElement = xmlTree.find("map")

            for prefElement in mapElement.items():
                prefKey = prefElement.get("name")

                if not prefKey is None:
                    prefValue = prefElement.text
                    sharedPreferences[prefKey] = prefValue

            # Clean up files 
            os.remove(sharedPrefFile)

        if key in sharedPreferences:
            return sharedPreferences[key]
        else:
            return None
            
    def setSharedPreferenceValue(self, packageName, key, value, valueType):
        appDir = "/data/data/" + packageName
        appDirContents = call(["adb", "shell", "ls", appDir])

        if not "shared_prefs" in appDirContents:
            return None

        sharedPrefDir =  appDir + "/shared_prefs"
        call(["adb", "pull", sharedPrefDir, TEMP_DIR])

        for sharedPrefFileName in os.listdir(TEMP_DIR):
            sharedPrefFile = os.path.join(TEMP_DIR, sharedPrefFileName)
            xmlTree = ElementTree.parse(sharedPrefFile)
            mapElement = xmlTree.find("map")
            keyFound = False
            changed = False

            for prefElement in mapElement.items():
                prefKey = prefElement.get("name")

                if not prefKey is None and prefKey == key:
                    if (not prefElement.get(prefKey) == value):
                        prefElement.text = value
                        changed = True

                    keyFound = True
                    break

            if not keyFound:
                newPrefElement = Element(valueType)
                newPrefElement.set("name", key)

                if valueType == "string":
                    newPrefElement.text = value
                else:
                    newPrefElement.set("value", value)

                mapElement.append(newPrefElement)
                changed = True

            if changed:
                xmlTree.write(sharedPrefFile)
                call(["adb", "push", sharedPrefFile, sharedPrefDir + "/" + sharedPrefFileName])

            # Clean up files 
            os.remove(sharedPrefFile)
        
