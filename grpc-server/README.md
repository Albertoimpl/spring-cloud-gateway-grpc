```shell
keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 -validity 3650
keytool -genkeypair -keyalg RSA -keysize 2048 -keystore keystore.jks -validity 3650
keytool -importkeystore -srckeystore keystore.jks -destkeystore keystore.p12 -deststoretype pkcs12
```
