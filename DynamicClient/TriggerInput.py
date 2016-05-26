#!/usr/bin/python

from settings import *

import sys
import string
import math
import traceback
from datetime import datetime
from z3 import *

# Variable names used for the Z3 constraint solver are determined by the code that generated them
#    IFA = IntelliDroid Framework Analysis
#    IAA = IntelliDroid App Analysis
#    IDC = IntelliDroid Dynamic Client

z3VariableTable = {
    "onLocationChanged" : {
        "<UpdateRecord>.mReceiver.mListener" : "IFAv16",
        "<UpdateRecord>.mReceiver.mAllowedResolutionLevel" : "IFAv7",
        "<UpdateRecord>.mRequest.getNumUpdates()" : "IFAv15",
        "<UpdateRecord>.mLastFixBroadcast.getElapsedRealtimeNanos()" : "IFAv10",
        "<UpdateRecord>.mLastFixBroadcast" : "IFAv9",
        "<UpdateRecord>.mRequest.getFastestInterval()" : "IFAv11",
        "<UpdateRecord>.mRequest.getSmallestDisplacement()" : "IFAv13",
        "<UpdateRecord>.mLastFixBroadcast.getLatitude()" : "IDCv1",
        "<UpdateRecord>.mLastFixBroadcast.getLongitude()" : "IDCv2",
    }
}

z3TriggerInputTable = {
    "onLocationChanged" : {
        "IFAv6" : "<Input1>.mIsFromMockProvider",
        "IFAv6" : "<ChainedInput1>.mIsFromMockProvider",
        "IFAv5" : "<Input2>",
        "IFAv5" : "<ChainedInput2>",
        #"v14" : "<Input1>.distanceTo(<UpdateRecord>.mLastFixBroadcast)",
        #"v2" : "<Input1>.mHasAccuracy",
        "IFAv4" : "<Input1>.mElapsedRealtimeNanos",
        "IFAv4" : "<ChainedInput1>.mElapsedRealtimeNanos",
        "IFAv3" : "<Input1>.mTime",
        "IFAv3" : "<ChainedInput1>.mTime",
        "IFAv0" : "<Input1>.mProvider",
        "IFAv0" : "<ChainedInput1>.mProvider",
        "IDCv3" : "<Input1>.latitude",
        "IDCv3" : "<ChainedInput1>.latitude",
        "IDCv4" : "<Input1>.longitude",
        "IDCv4" : "<ChainedInput1>.longitude"
    }
}

z3CodeTable = {
    "onLocationChanged" : open(SCRIPT_DIR + "/../FrameworkAnalysis/z3output/Landroid.location.LocationListener.onLocationChanged.py", "r").read()
}

triggerInputTable = {
    "sms" : {
        "<Input2>.getExtras().get(pdus).<SmsMessage>.getDisplayOriginatingAddress()" : "senderNumber",
        "<ChainedInput2>.getExtras().get(pdus).<SmsMessage>.getDisplayOriginatingAddress()" : "senderNumber",
        "<Input2>.getExtras().get(pdus).<SmsMessage>.getOriginatingAddress()" : "senderNumber",
        "<ChainedInput2>.getExtras().get(pdus).<SmsMessage>.getOriginatingAddress()" : "senderNumber",
        "<Input2>.getExtras().get(pdus).<SmsMessage>.getMessageBody()" : "userData",
        "<ChainedInput2>.getExtras().get(pdus).<SmsMessage>.getMessageBody()" : "userData",
        "<Input2>.getExtras().get().<SmsMessage>.getDisplayOriginatingAddress()" : "senderNumber",
        "<ChainedInput2>.getExtras().get().<SmsMessage>.getDisplayOriginatingAddress()" : "senderNumber",
        "<Input2>.getExtras().get().<SmsMessage>.getOriginatingAddress()" : "senderNumber",
        "<ChainedInput2>.getExtras().get().<SmsMessage>.getOriginatingAddress()" : "senderNumber",
        "<Input2>.getExtras().get().<SmsMessage>.getMessageBody()" : "userData",
        "<ChainedInput2>.getExtras().get().<SmsMessage>.getMessageBody()" : "userData",
        "System.currentTimeMillis()" : "time"
    },
    "intent" : {
        "<Input2>.getAction()": "-a",
        "<ChainedInput2>.getAction()": "-a"
    },
    "ui" : {
        "id": "id",
        "position": "position",
        "<View>.getId()": "id",
        "<AdapterView>.getId()": "adapterId",

        "keyCode": "keyCode",
        "<KeyEvent>.getKeyCode()": "keyCode",
        "<KeyEvent>.getAction()": "keyAction",
        "<KeyEvent>.getFlags()": "keyFlags",
        "<KeyEvent>.getMetaState()": "keyMetaState",
        "<KeyEvent>.getNumber()": "keyCharacter",
        "<KeyEvent>.getUnicodeChar()": "keyCharacter",
        "<KeyEvent>.getRepeatCount()": "keyRepeatCount",

        "<MotionEvent>.getAction()": "motionAction",
        "<MotionEvent>.getActionMasked()": "motionAction",
        "<MotionEvent>.getX()": "motionX",
        "<MotionEvent>.getY()": "motionY",
        "<MotionEvent>.getMetaState()": "motionMetaState",
        "<MotionEvent>.getPressure()": "motionPressure",
        "<MotionEvent>.getXPrecision()": "motionXPrecision",
        "<MotionEvent>.getYPrecision()": "motionYPrecision"
    }
}

def generateLocationInput(locationInfo):
    z3Code = ""

    if "constraintsFile" in locationInfo:
        constraintFile = appDir + "/" + locationInfo["constraintsFile"]
        z3Code = open(constraintFile).read();

    # Add framework constraints
    z3Code += z3CodeTable["onLocationChanged"]

    locationTable = z3VariableTable["onLocationChanged"]
    prevLocationLatitude = float(0.0)
    prevLocationLongitude = float(0.0)

    # Parse information received from LocationManagerService
    for attribute in locationInfo:
        data = string.split(attribute, ":")

        if (len(data) < 2):
            continue

        fieldName = data[0]
        fieldValue = data[1]

        if fieldName == "<UpdateRecord>.mLastFixBroadcast.getLatitude()":
            prevLocationLatitude = float(fieldValue)
        elif fieldName == "<UpdateRecord>.mLastFixBroadcast.getLongitude()":
            prevLocationLongitude = float(fieldValue)

        try:
            z3Variable = locationTable[fieldName]

            z3Code += "s.add("
            z3Code += z3Variable
            z3Code += " == "
            z3Code += fieldValue
            z3Code += ")\n"
        except KeyError:
            pass

    #print("\nGenerated code: ")
    #print(z3Code)

    # Use Z3 to solve constraints
    s = Solver()

    # Add extra variables to help solve GPS constraints
    IDCv1 = Real('IDCv1')   # prev latitude
    IDCv2 = Real('IDCv2')   # prev longitude
    IDCv3 = Real('IDCv3')   # new latitude
    IDCv4 = Real('IDCv4')   # new longitude

    try:
        exec(z3Code)
    except Z3Exception as e:
        sys.stderr.write("\nError running Z3 for " + constraintFile + "\n")
        sys.stderr.write(str(e) + "\n")
        traceback.print_exc(file=sys.stderr)
        return None

    # Compute the new GPS coordinates (use the distanceTo value for both dx and dy)
    # http://stackoverflow.com/questions/2839533/adding-distance-to-a-gps-coordinate
    s.add(IDCv3 == (IDCv1 + (180 / math.pi) * (IFAv14 / 6378137)))
    s.add(IDCv4 == (IDCv2 + (180 / math.pi) * (IFAv14 / 6378137) / math.cos(math.radians(prevLocationLatitude))))
    #newLocationLatitude = prevLocationLatitude + (180.0 / math.pi) * (locationDistanceTo / 6378137.0) 
    #newLocationLongitude = prevLocationLongitude + (180.0 / math.pi) * (locationDistanceTo / 6378137.0) / math.cos(math.radians(prevLocationLatitude))

    # Create model for the constraint solver
    if s.check() == unsat:
        sys.stderr.write("\nConstraints not satisfiable for " + constraintFile + "\n")
        return None

    model = s.model()

    # Generate string to inject into device
    locationInputTable = z3TriggerInputTable["onLocationChanged"]
    locationInput = ""

    # Generate location information from constraints
    for variable, inputField in locationInputTable.iteritems():
        try:
            locationFieldStr = str(model.evaluate(eval(variable), model_completion=True))
            if "/" in locationFieldStr:
                locationFieldStr += ".0"
            locationField = eval(locationFieldStr)
            locationInput += inputField + ":" + str(locationField)
        except:
            print("Exception when evaluating variable: " + variable)
            print "  Type: ", sys.exc_info()[0]
            pass


        locationInput += " "
    
    #print("\nLocation input: ")
    #print(locationInput)

    return locationInput


def generateSmsInput(appDir, constraintInfo, timeInfo):
    if "constraintsFile" not in constraintInfo:
        return ""

    constraintFile = appDir + "/" + constraintInfo["constraintsFile"]
    variables = constraintInfo["variables"]

    z3Code = open(constraintFile).read();

    # Use Z3 to solve constraints
    s = Solver()

    try:
        exec(z3Code)
    except Z3Exception as e:
        sys.stderr.write("\nError running Z3 for " + constraintFile + "\n")
        sys.stderr.write(str(e) + "\n")
        traceback.print_exc(file=sys.stderr)
        return None

    # Add constraint for system time, if necessary
    if "<SystemPrevTime>" in variables:
        timeMillis = eval(timeInfo) * 1000
        s.add(eval(variables["<SystemPrevTime>"]) == timeMillis) 

    # Create model for the constraint solver
    if s.check() == unsat:
        sys.stderr.write("\nConstraints not satisfiable for " + constraintFile + "\n")
        return None

    model = s.model()

    # Generate string to inject into device
    smsTriggerInputTable = triggerInputTable["sms"]
    message = ""

    for varName, inputField in smsTriggerInputTable.iteritems():
        if varName in variables:
            z3Var = variables[varName]
            z3ResolvedVar = str(model.evaluate(eval(z3Var), model_completion=True))
            resolvedValue = None

            if z3Var in constraintInfo["strings"]:
                if z3ResolvedVar in constraintInfo["stringMap"]:
                    resolvedValue = "\"" + constraintInfo["stringMap"][z3ResolvedVar] + "\""
            else:
                resolvedValue = z3ResolvedVar

            if resolvedValue is not None:
                message += inputField + ":" + resolvedValue
                message += " "

    # Handle date formatting in a special case
    if "DateFormat(MMddyyyy)(<CurrentDate>)" in variables:
        z3Var = variables["DateFormat(MMddyyyy)(<CurrentDate>)"]
        z3ResolvedVar = str(model.evaluate(eval(z3Var), model_completion=True))
        resolvedValue = constraintInfo["stringMap"][z3ResolvedVar]

        smsDate = datetime.strptime(resolvedValue, "%m%d%Y")
        epoch = datetime.fromtimestamp(0)
        timeSinceEpoch = smsDate - epoch
        millisSinceEpoch = timeSinceEpoch.total_seconds() * 1000

        message += "time:" + str(long(millisSinceEpoch))
        message += " "

    return message

def generateIntentInput(constraintInfo):
    if "constraintsFile" not in constraintInfo:
        return ""

    constraintFile = appDir + "/" + constraintInfo["constraintsFile"]
    variables = constraintInfo["variables"]

    z3Code = open(constraintFile).read();

    # Use Z3 to solve constraints
    s = Solver()

    try:
        exec(z3Code)
    except Z3Exception as e:
        sys.stderr.write("\nError running Z3 for " + constraintFile + "\n")
        sys.stderr.write(str(e) + "\n")
        traceback.print_exc(file=sys.stderr)
        return None

    # Create model for the constraint solver
    if s.check() == unsat:
        sys.stderr.write("\nConstraints not satisfiable for " + constraintFile + "\n")
        return None

    model = s.model()

    intentTriggerInputTable = triggerInputTable["intent"]
    message = ""

    for varName, inputField in intentTriggerInputTable.iteritems():
        if varName in variables:
            z3Var = variables[varName]
            z3ResolvedVar = str(model.evaluate(eval(z3Var), model_completion=True))
            resolvedValue = None

            if z3Var in constraintInfo["strings"] and z3ResolvedVar in constraintInfo["stringMap"]:
                resolvedValue = "\"" + constraintInfo["stringMap"][z3ResolvedVar] + "\""
            else:
                resolvedValue = z3ResolvedVar

            message += inputField + " " + resolvedValue
            message += " "

    return message

