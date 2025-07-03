import 'package:flutter/material.dart';
import 'background_locator.dart';

class AutoStopHandler extends WidgetsBindingObserver {
  @override
  Future<void> didChangeAppLifecycleState(AppLifecycleState state) async {
    switch (state) {
      case AppLifecycleState.resumed:
        // Code à exécuter quand l'app revient au premier plan
        break;
      case AppLifecycleState.inactive:
        // Code à exécuter quand l'app devient inactive (ex: un appel)
        break;
      case AppLifecycleState.paused:
        // Code à exécuter quand l'app est en pause (background)
        break;
      case AppLifecycleState.detached:
        // Code à exécuter quand l'app est détachée
        break;
      case AppLifecycleState.hidden:
        // Flutter 3.13+ : souvent rien à faire ici (multi-fenêtre)
        break;
    }
  }
}
