# Android-Pdfjs
Custom version of Mozilla Pdfjs for android

[![](https://jitpack.io/v/classiczires/Android-Pdfjs.svg)](https://jitpack.io/#classiczires/Android-Pdfjs)


## Installation

### Gradle
Add this to the root build.gradle at the end of repositories (**WARNING:** Make sure you add this under **allprojects** not under buildscript):
```Gradle
allprojects {
        repositories {
                ...
                maven { url 'https://jitpack.io' }
        }
}
```

Add the dependency to the project build.gradle:
```Gradle
dependencies {
	        implementation 'com.github.classiczires:Android-Pdfjs:1.0.0'
}
```
