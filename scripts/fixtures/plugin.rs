// Minimal real native ABI fixture; no external crates or provider accounts.
use std::ffi::{c_char, c_void, CStr};
use std::ptr;

fn base64(bytes: &[u8]) -> String {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in bytes.chunks(3) {
        let value = ((chunk[0] as u32) << 16) | ((chunk.get(1).copied().unwrap_or(0) as u32) << 8) | chunk.get(2).copied().unwrap_or(0) as u32;
        out.push(TABLE[((value >> 18) & 63) as usize] as char);
        out.push(TABLE[((value >> 12) & 63) as usize] as char);
        out.push(if chunk.len() > 1 { TABLE[((value >> 6) & 63) as usize] as char } else { '=' });
        out.push(if chunk.len() > 2 { TABLE[(value & 63) as usize] as char } else { '=' });
    }
    out
}

#[repr(C)]
pub struct Buffer { ptr: *mut u8, len: usize }
type HostCall = unsafe extern "C" fn(*mut c_void, *const c_char, *const u8, usize, *mut Buffer) -> i32;
type Free = unsafe extern "C" fn(*mut u8, usize);
#[repr(C)]
pub struct Host { version: u32, context: *mut c_void, call: HostCall, free: Free }
#[repr(C)]
pub struct Plugin { version: u32, call: unsafe extern "C" fn(*const c_char, *const u8, usize, *mut Buffer) -> i32, free: Free, shutdown: extern "C" fn() }
static mut HOST: *const Host = ptr::null();

unsafe extern "C" fn free(data: *mut u8, length: usize) {
    if !data.is_null() { drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(data, length))); }
}
extern "C" fn shutdown() {}
unsafe fn output(response: *mut Buffer, text: String) -> i32 {
    let bytes = text.into_bytes().into_boxed_slice();
    (*response).len = bytes.len();
    (*response).ptr = Box::into_raw(bytes) as *mut u8;
    0
}
unsafe extern "C" fn call(method: *const c_char, _request: *const u8, _length: usize, response: *mut Buffer) -> i32 {
    let name = CStr::from_ptr(method).to_str().unwrap();
    let request_text = std::str::from_utf8(std::slice::from_raw_parts(_request, _length)).unwrap();
    let result = match name {
        "plugin.register" | "plugin.reconfigure" => r#"{"schema_version":6,"metadata":{"Name":"Native fixture","Version":"1.0.0","ConfigFields":[{"Name":"greeting","Type":"string"}]},"capabilities":{"auth_provider":true,"executor":true,"quota_provider":true,"management_api":true,"request_interceptor":true,"command_line_plugin":true}}"#,
        "command_line.register" => r#"{"Flags":[{"Name":"greet","Type":"bool","DefaultValue":"false"}]}"#,
        "command_line.execute" => r#"{"Stdout":"cGx1Z2luIGNvbW1hbmQgb2sK","ExitCode":0}"#,
        "request.intercept_before" => {
            assert!(request_text.contains("\"ToFormat\":\"\""));
            if request_text.contains("blocked-model") {
                r#"{"Terminate":true,"StatusCode":418,"ResponseBody":"eyJibG9ja2VkIjp0cnVlfQ=="}"#
            } else { "{}" }
        },
        "request.intercept_after" => {
            assert!(!request_text.contains("\"ToFormat\":\"\""));
            "{}"
        },
        "auth.parse" => r#"{"Handled":true,"Auth":{"Provider":"fixture","FileName":"imported.json","StorageJSON":"eyJ0eXBlIjoiZml4dHVyZSIsImFjY2Vzc190b2tlbiI6ImZpeHR1cmUtdG9rZW4ifQ=="}}"#,
        "executor.http_request" => r#"{"StatusCode":201,"Headers":{"Content-Type":["application/json"]},"Body":"eyJwbHVnaW4iOnRydWV9"}"#,
        "auth.identifier" | "executor.identifier" | "quota.identifier" => r#"{"identifier":"fixture"}"#,
        "management.register" => r#"{"routes":[{"Method":"GET","Path":"/v8/management/plugins/fixture/echo"},{"Method":"GET","Path":"/v8/management/plugins/fixture/nested"}],"resources":[{"Path":"/v0/resource/plugins/fixture/panel","Menu":"Native panel"}]}"#,
        "management.handle" if request_text.contains("/nested") => {
            let mut host_response = Buffer { ptr: ptr::null_mut(), len: 0 };
            let body = br#"{"entry_protocol":"openai","exit_protocol":"openai","model":"fixture-model","stream":false,"body":"eyJtb2RlbCI6ICJmaXh0dXJlLW1vZGVsIiwgIm1lc3NhZ2VzIjogW3sicm9sZSI6ICJ1c2VyIiwgImNvbnRlbnQiOiAibmVzdGVkIn1dfQ=="}"#;
            ((*HOST).call)((*HOST).context, b"host.model.execute\0".as_ptr() as *const c_char, body.as_ptr(), body.len(), &mut host_response);
            let host_result = std::slice::from_raw_parts(host_response.ptr, host_response.len);
            let encoded = base64(host_result);
            ((*HOST).free)(host_response.ptr, host_response.len);
            return output(response, format!("{{\"ok\":true,\"result\":{{\"StatusCode\":200,\"Body\":\"{encoded}\"}}}}"));
        },
        "management.handle" => r#"{"StatusCode":200,"Headers":{"Content-Type":["application/json"]},"Body":"eyJmcm9tIjoibmF0aXZlLW1hbmFnZW1lbnQifQ=="}"#,
        "auth.login.start" => r#"{"Provider":"fixture","URL":"http://localhost/fixture-login","State":"fixture-state","Metadata":{}}"#,
        "auth.login.poll" => r#"{"Status":"success","Auth":{"Provider":"fixture","FileName":"fixture.json","StorageJSON":"eyJ0eXBlIjoiZml4dHVyZSIsImFjY2Vzc190b2tlbiI6ImZpeHR1cmUtdG9rZW4ifQ=="}}"#,
        "quota.describe" => r#"{"display_name":"Fixture quota","supported_providers":["fixture"],"supports_reset":true}"#,
        "quota.fetch" => {
            let mut host_response = Buffer { ptr: ptr::null_mut(), len: 0 };
            let body = b"{}";
            ((*HOST).call)((*HOST).context, b"host.auth.list\0".as_ptr() as *const c_char, body.as_ptr(), body.len(), &mut host_response);
            let host_result = std::slice::from_raw_parts(host_response.ptr, host_response.len);
            assert!(std::str::from_utf8(host_result).unwrap().contains("fixture.json"));
            ((*HOST).free)(host_response.ptr, host_response.len);
            r#"{"provider":"fixture","status":"ok","buckets":[{"id":"requests","remaining":42}]}"#
        },
        "quota.reset" => r#"{"success":true}"#,
        "executor.execute" => r#"{"Payload":"eyJjaG9pY2VzIjpbeyJtZXNzYWdlIjp7InJvbGUiOiJhc3Npc3RhbnQiLCJjb250ZW50IjoibmF0aXZlIHBsdWdpbiB3b3JrcyJ9fV0sInVzYWdlIjp7InByb21wdF90b2tlbnMiOjMsImNvbXBsZXRpb25fdG9rZW5zIjo1fX0=","Headers":{"x-native-fixture":["true"]}}"#,
        "executor.execute_stream" => r#"{"headers":{},"chunks":[{"Payload":"ZGF0YTogeyJjaG9pY2VzIjpbeyJkZWx0YSI6eyJjb250ZW50IjoibmF0aXZlIHN0cmVhbSJ9fV19Cgo="},{"Payload":"ZGF0YTogW0RPTkVdCgo="}]}"#,
        "plugin.quiesce" | "plugin.shutdown" => "{}",
        _ => return output(response, r#"{"ok":false,"error":{"code":"unknown","message":"Unknown fixture method"}}"#.into()),
    };
    output(response, format!("{{\"ok\":true,\"result\":{result}}}"))
}
#[no_mangle]
pub unsafe extern "C" fn cliproxy_plugin_init(host: *const Host, api: *mut Plugin) -> i32 {
    HOST = host;
    ptr::write(api, Plugin { version:1, call, free, shutdown });
    0
}
