내 화면녹화 (MyRecorder) - 안드로이드 앱 소스

[기능]
- 녹화 시작 버튼 → 화면 녹화 시작
- 녹화 중 화면 위에 떠 있는 [● 00:00  ■ 정지] 버튼 (드래그로 위치 이동 가능)
- 정지하면 갤러리(Movies/MyRecorder 폴더)에 mp4로 자동 저장
- 알림창에서도 정지 가능, 마이크 소리 녹음 옵션
- Android 10 이상 지원

[APK 만드는 방법]
1. PC에 Android Studio 설치 (https://developer.android.com/studio)
2. 이 zip 압축 해제 → Android Studio에서 File > Open → MyRecorder 폴더 선택
3. 처음 열면 Gradle 동기화(Sync)가 자동으로 진행됨 (몇 분 소요, 인터넷 필요)
4. 메뉴 Build > Build App Bundle(s) / APK(s) > Build APK(s)
5. 완료 후 app/build/outputs/apk/debug/app-debug.apk 생성
6. 이 APK를 휴대폰으로 옮겨 설치 ("출처를 알 수 없는 앱 설치" 허용 필요)
   * 또는 휴대폰을 USB로 연결(개발자 옵션 > USB 디버깅 켜기) 후 ▶ Run 버튼으로 바로 설치

[처음 실행할 때]
- '다른 앱 위에 표시' 권한 → 켜기 (정지 버튼을 띄우는 데 필요)
- 알림 권한 → 허용
- "화면 녹화를 시작할까요?" → 시작 (매번 뜸, 안드로이드 보안 정책)
