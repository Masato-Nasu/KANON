KANON v0.1.0 / Windows 10/11 signed release build

初回だけ:
1. ZIPを展開します。
2. CREATE_RELEASE_KEY.cmd をダブルクリックします。
3. release-key\kanon-release.jks と keystore.properties が作成されます。
4. この2つは今後のアップデートにも必要なので、必ず安全な場所へバックアップしてください。

Release APKを作る:
1. BUILD_AND_INSTALL.cmd をダブルクリックします。
2. Android SDK Platform 35以上、Android SDK Build-Tools、Java 17以上が必要です。
3. スクリプトは assembleRelease を実行します。
4. apksigner で署名を検証します。
5. 成功するとプロジェクト直下に KANON-v0.1.0.apk が作成されます。
6. この KANON-v0.1.0.apk を GitHub Releases にアップロードしてください。

注意:
- keystore.properties と release-key\ は .gitignore 対象です。GitHubへアップロードしないでください。
- 以前のDebug APKが端末に入っている場合、署名が違うためRelease APKを上書きインストールできないことがあります。その場合は古いKANONをアンインストールしてからRelease版を入れてください。
- アンインストールするとKANONのアプリデータが消えるため、OpenAI API keyは再入力が必要です。
- このrelease signing keyを失うと、同じアプリID jp.masatolab.kanon の将来バージョンを既存ユーザーへ更新配布できなくなります。
