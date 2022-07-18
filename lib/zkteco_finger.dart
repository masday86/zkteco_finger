import 'dart:async';

import 'package:flutter/services.dart';

class ZktecoFinger {
  static const MethodChannel _channel = MethodChannel('zkteco_finger');

  static Future<String?> get platformVersion async {
    final String? version = await _channel.invokeMethod('getPlatformVersion');
    return version;
  }

  static const EventChannel statusChangeStream =
      EventChannel('dev.myhd.zkteco_finger/status_change');
  static const EventChannel imageStream =
      EventChannel('dev.myhd.zkteco_finger/finger_image');

  static Future<bool?> openConnection({bool isLogEnabled = true}) async {
    return _channel.invokeMethod(
        'openConnection', <String, bool>{'isLogEnabled': isLogEnabled});
  }

  static Future<bool?> closeConnection() async {
    return _channel.invokeMethod('closeConnection');
  }

  static Future<bool?> startListen({required String userId}) async {
    return _channel.invokeMethod('startListen', <String, String>{'id': userId});
  }

  static Future<bool?> stopListen() async {
    return _channel.invokeMethod('stopListen');
  }

  static Future<bool?> enroll({required String userId}) async {
    return _channel.invokeMethod('enroll', <String, String>{'id': userId});
  }

  static Future<bool?> verify({required String userId}) async {
    return _channel.invokeMethod('verify', <String, String>{'id': userId});
  }

  static Future<bool> registerFinger(
      {required String userId, required String dataBase64}) async {
    final bool success = await _channel.invokeMethod(
        'register', <String, String>{'id': userId, 'data': dataBase64});
    return success;
  }

  static Future<bool> clearFingerDatabase() async {
    return await _channel.invokeMethod('clear');
  }

  static Future<bool> isDeviceSupported() async {
    return await _channel.invokeMethod('isDeviceSupported');
  }
}
