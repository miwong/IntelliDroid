#!/usr/bin/python

from settings import *
import TriggerInput
import AndroidCommunication
import Completer

import string
import json
import readline
import os

class IntelliDroidDynamicClient:
    socket = None

    def __init__(self):
        self.alarmListenerID = "0"
        self.locationListenerID = "0"
        self.socketInfoRead = None
        self.appDir = None
        self.appInfo = None
        self.androidComm = AndroidCommunication.AndroidCommunication(self.onInput)

    def run(self):
        # Set up command line environment
        completer = Completer.Completer()
        readline.parse_and_bind("tab: complete")
        readline.set_completer(completer.complete)
        readline.set_completer_delims(' \t\n;')

        # Initiate connection to device (via IntelliDroidService)
        self.androidComm.connectToDevice()

        while True:
            data = raw_input("> ")
            inputs = string.split(data)

            if not inputs:
                continue

            command = inputs[0]

            if command == "HELP":
                self.printHelp()
            elif command == "INSTALL":
                if len(inputs) >= 2:
                    self.androidComm.installApplication(inputs[1])
            elif command == "START":
                if len(inputs) >= 2:
                    self.startApplication(inputs[1])
            elif command == "TRIGGER":
                if len(inputs) >= 2:
                    self.triggerCallback(inputs[1])
            elif command == "INFO":
                if len(inputs) >= 3:
                    self.androidComm.sendSocket("INFO " + inputs[1] + " " + inputs[2])
            elif command == "EXECUTE":
                command = " ".join(inputs[1:])
                self.androidComm.sendSocket(command.encode("utf-8"))
            #elif command == "TEST":
            #    self.triggerAll()
            elif command == "CLOSE":
                self.androidComm.stopAnalysis()
                break
            elif command == "KILL":
                self.androidComm.stopAnalysis()
                self.androidComm.killEmulator()
                break

        self.androidComm.disconnect()

    def onInput(self, data):
        if not data:
            return

        print("\n>>> Received: " + data)
        inputs = string.split(data)

        if not inputs:
            return

        command = inputs[0]
    
        if command == "INFO":
            self.socketInfoRead = inputs[3:]
        elif command == "CLOSE":
            return
        elif command == "NEW_LISTENER" and inputs[1] == "alarm":
            self.alarmListenerID = inputs[2]
        elif command == "NEW_LISTENER" and inputs[1] == "location":
            self.locationListenerID = inputs[2]

    def printHelp(self):
        for commandItem in Completer.commandDescriptions:
            print ("%-20s %s" % (commandItem[0], commandItem[1]))

    def startApplication(self, appInfoDir):
        appInfoFile = open(appInfoDir + "/appInfo.json")

        self.appDir = appInfoDir
        self.appInfo = json.load(appInfoFile)

        self.androidComm.startAnalysisForPackage(self.appInfo["packageName"])

        mainActivityName = self.appInfo["packageName"] + "/" + self.appInfo["mainActivity"]
        self.androidComm.startActivity(mainActivityName)

    #def triggerAll(self):
    #    for callbackNumber in sorted(self.appInfo["callPaths"], key = lambda d: int(d)):
    #        print(">>> Triggering path: " + callbackNumber)
    #        self.triggerCallback(callbackNumber)
    #    print(">>> Test completed\n")

    def triggerCallback(self, callbackNumber):
        if self.appInfo is None:
            print "Run START command to specify app directory before triggering paths"
            return

        constraintInfo = self.appInfo["callPaths"][callbackNumber]["eventChain"]

        for constraint in constraintInfo:
            if constraint["type"] == "location":
                self.triggerLocation(self.locationListenerID)
            elif constraint["type"] == "sms":
                self.triggerSms(constraint)
            elif constraint["type"] == "boot":
                self.triggerBoot()
            elif constraint["type"] == "alarm":
                self.triggerAlarm(self.alarmListenerID)
            elif constraint["type"] == "time":
                self.triggerTime()
            elif constraint["type"] == "activity":
                self.triggerActivity(constraint)
            elif constraint["type"] == "service":
                self.triggerService(constraint)
            else:
                print("Not implemented: " + constraint["type"])


    def triggerLocation(self, listenerID):
        self.androidComm.sendSocket("INFO location " + str(listenerID))

        # Wait until device returns location info
        while self.socketInfoRead is None:
            pass

        listenerInfo = self.socketInfoRead
        self.socketInfoRead = None

        # Solve constraints to determine the location data to inject
        location = TriggerInput.generateLocationInput(listenerInfo) 
        if location is not None:
            self.androidComm.sendSocket("TRIGGER location " + str(listenerID) + " " + location)

    def triggerAlarm(self, listenerID):
        self.androidComm.sendSocket("INFO alarm " + str(listenerID))
        while self.socketInfoRead is None:
            pass

        listenerInfo = self.socketInfoRead
        self.socketInfoRead = None
        alarmWhen = string.split(listenerInfo[0], ":")[1]

        self.androidComm.sendSocket("TRIGGER alarm " + str(listenerID) + " " + alarmWhen)

    def triggerSms(self, constraintInfo):
        self.androidComm.sendSocket("INFO time")
        while self.socketInfoRead is None:
            pass

        timeInfo = self.socketInfoRead
        self.socketInfoRead = None
        time = string.split(timeInfo[0], ":")[1]

        message = TriggerInput.generateSmsInput(self.appDir, constraintInfo, time)
        if message is not None:
            command = "TRIGGER sms 0 " + message
            self.androidComm.sendSocket(command.encode("utf-8"))

    def triggerBoot(self):
        self.androidComm.sendSocket("TRIGGER boot")

    def triggerTime(self):
        self.androidComm.sendSocket("INFO time")
        while self.socketInfoRead is None:
            pass

        timeInfo = self.socketInfoRead
        self.socketInfoRead = None
        timeStr = string.split(timeInfo[0], ":")[1]
        time = eval(timeStr)
        newTime = time + 300000
        self.androidComm.sendSocket("TRIGGER time " + str(newTime));

    def triggerActivity(self, constraintInfo):
        if "component" in  constraintInfo:
            self.androidComm.startActivity(self.appInfo["packageName"] + "/" + constraintInfo["component"])
        else:
            print("Could not find activity name")

    def triggerService(self, constraintInfo):
        if "component" in  constraintInfo:
            self.androidComm.startService(self.appInfo["packageName"] + "/" + constraintInfo["component"])
        else:
            print("Could not find service name")

# =============================================================================

def main():
    if os.path.isfile(HISTORY_FILE):
        readline.read_history_file(HISTORY_FILE)

    if not os.path.isdir(TEMP_DIR):
        os.mkdir(TEMP_DIR)

    # Requires Python 3.3...
    #print(">>> Waiting for Android device...")
    #call(["adb", "wait-for-device"], timeout=30)
    #print (">>> Android device found!")

    try:
        client = IntelliDroidDynamicClient()
        client.run()
    finally:
        try:
            client.androidComm.stopAnalysis()
        except:
            pass

        os.rmdir(TEMP_DIR)
        readline.set_history_length(100)
        readline.write_history_file(HISTORY_FILE)

# =============================================================================

if __name__ == "__main__":
    main()

