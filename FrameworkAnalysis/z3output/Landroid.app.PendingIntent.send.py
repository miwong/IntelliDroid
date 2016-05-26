# Trigger: com.android.server.LocationManagerService.setTestProviderEnabled(Ljava/lang/String;Z)V
# Callback: android.app.PendingIntent.send(Landroid/content/Context;ILandroid/content/Intent;Landroid/app/PendingIntent$OnFinished;Landroid/os/Handler;Ljava/lang/String;)V

IFAv0 = Real('IFAv0')    # <Input2>

s.add(Or(Not((IFAv0 == 0)), (IFAv0 == 0)))

