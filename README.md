# Biwa Swim

琵琶湖でのアクティビティやトラッキングを想定した、GNSS/GPS連携リアルタイム位置追跡 Android アプリケーションです。

---

## 📌 プロジェクト概要

**Biwa Swim** は、みちびきから受信した位置情報（NMEAデータ）を解析し、国土地理院の高解像度航空写真マップ上にリアルタイムで現在地を可視化・トラッキングするAndroidアプリです。

現在はUSBシリアル経由でのGNSSデバイス接続に対応しており、今後は無線通信（Bluetooth）への対応も予定しています。

---

## ✨ 主な機能

- **🗺️ リアルタイムマッピング (MapLibre)**
  - MapLibre Native SDK を活用した滑らかな地図描画
  - 国土地理院（GSI）のシームレス空中写真タイルを採用
  - 琵琶湖を中心とした鳥瞰（チルト）3Dカメラビュー
  - リアルタイムなマーカー追従表示

- **🔌 みちびきとのシリアル通信 (USB)**
  - FTDI等のUSB-UART変換チップ（USBシリアル通信）に対応
  - USB機器の自動検出・接続およびパーミッション処理
  - 受信バッファからのストリームデータ解析

- **🛰️ NMEAセンテンスのリアルタイム解析**
  - `$GNGLL`（地理位置情報センテンス）の抽出・解析
  - DMS（度分秒）形式から十進角（Decimal Degrees）形式への座標変換

- **📶 接続ステータス表示**
  - デバイスの接続状態（Connected / Disconnected）を画面上にリアルタイム表示

---

## 🚀 今後の開発予定 (Roadmap)

- **📶 Bluetooth 接続の対応**
  - USB有線接続に加え、Bluetooth（Bluetooth Classic SPP / BLE）経由での外部GPS・各種センサーとのワイヤレス接続機能を追加予定
- **📊 ログ記録・トラッキング履歴**
  - 移動ルートの軌跡（トラックライン）描画・GPX等のログ保存/エクスポート
- **⏱️ スピード・移動距離・経過時間の計測・UI拡張**
  - スイム/ボート等のアクティビティ向けダッシュボード機能
- **🛟 その他安全を確保するための機能**
  - 水分補給を促すアラート
  - 海岸からの距離が離れすぎたときのアラート


---

## 🛠️ 技術スタック

| カテゴリ | 技術 / ライブラリ |
| :--- | :--- |
| **言語** | Kotlin |
| **プラットフォーム** | Android (minSdk: 24 / targetSdk: 37) |
| **地図描画** | [MapLibre Native Android SDK](https://maplibre.org/) (ラスタタイル: 国土地理院 シームレス写真) |
| **USBシリアル通信** | [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) |
| **UI** | ConstraintLayout, Material Design |

---

## 📂 プロジェクト構成

```
biwaswim/
├── app/
│   ├── src/main/
│   │   ├── java/com/rencon/biwaswim/
│   │   │   └── MainActivity.kt          # メインロジック (USB制御・NMEA解析・Map表示)
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml  # メイン画面レイアウト (Map, ステータス表示)
│   │   │   └── xml/device_filter.xml     # 対応USBデバイス設定
│   │   └── AndroidManifest.xml          # 権限・USB Intent フィルタ設定
│   └── build.gradle.kts                 # アプリ依存関係・ビルド設定
├── build.gradle.kts
└── README.md
```

---

## ⚙️ 動作要件・セットアップ

1. **Android端末**: USB Host（OTG）機能および（将来的に）Bluetooth機能を備えた端末 (Android 7.0 / API 24 以上)
2. **GNSSデバイス**: NMEAデータ（`$GNGLL`等）を出力するUSBシリアル対応GNSS/GPSレシーバー
3. **ビルド環境**: Android Studio Ladybug 以降 / JDK 11
