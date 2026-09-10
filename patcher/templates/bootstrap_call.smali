# Injected into the host Application's attachBaseContext(Context), immediately before
# the method's final `return-void`. p0 = this (the Application, which is also a Context),
# so we pass it for both the Application and Context params of start().
#
# The patcher inserts exactly this one instruction (indentation preserved). It needs no
# extra registers: attachBaseContext already declares enough locals, and we only read p0.
#
# {APP_CLASS} and {BOOTSTRAP_CLASS} are substituted by pancakeify.py.

    invoke-static {p0, p0}, L{BOOTSTRAP_CLASS};->start(Landroid/app/Application;Landroid/content/Context;)V
