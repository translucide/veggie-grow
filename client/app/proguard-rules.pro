# Keep model POJOs intact for Gson (reflection-based (de)serialization).
-keep class ca.translucide.veggiegrow.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
