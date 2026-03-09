use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "C" fn Java_de_codevoid_aWayToGo_RustBridge_greetFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let output = env.new_string("Hello from Rust!").unwrap();
    output.into_raw()
}
