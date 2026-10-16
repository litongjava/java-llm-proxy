// Offline reference only. Deterministic randomness must never be used for real credentials.
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';

const [sourceRoot, outputDirectory] = process.argv.slice(2);
if (!sourceRoot || !outputDirectory) throw new Error('Expected channel-pack source directory and output directory');
const wasmPath = resolve(sourceRoot, 'qoder-auth-wasm.wasm');
const expected = '6419471effa631519def7797d76d7ede38b9fcfa9a83c2148c8ef5d43355b43d';
if (createHash('sha256').update(await readFile(wasmPath)).digest('hex') !== expected) throw new Error('Unexpected WASM hash');
let randomState = 123456789;
let randomBytes = 0;
function fill(view) {
  for (let i = 0; i < view.length; i++) {
    randomState = (Math.imul(randomState, 1664525) + 1013904223) >>> 0;
    view[i] = randomState >>> 24;
    randomBytes++;
  }
  return view;
}
Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues: fill } });
Date.now = () => 1802649600000;
// Load the existing bridge with only its asset path changed and private glue exported.
const original = await readFile(join(sourceRoot, 'qoder-wasm.ts'), 'utf8');
const adapted = original.replace(/const WASM_PATH = [^\n]+/, `const WASM_PATH = ${JSON.stringify(wasmPath)}`) + '\nexport { createGlue };\n';
const bridgePath = join(outputDirectory, 'reference-bridge.mts');
await writeFile(bridgePath, adapted);
const { createGlue } = await import(pathToFileURL(bridgePath));
const glue = await createGlue();
const userJson = JSON.stringify({uid:'probe-user', security_oauth_token:'synthetic-token-not-valid', organization_id:'', organization_tags:[], data_policy_agreed:false});
const metadataJson = JSON.stringify({client_type:'5',business_product:'cli',business_type:'agent',scene:'assistant'});
const payloadJson = JSON.stringify({request_id:'probe-request',request_set_id:'probe-request',chat_record_id:'probe-request',session_id:'probe-session',stream:true,chat_task:'FREE_INPUT',chat_context:{text:'hello',features:[],extra:{context:[],modelConfig:{key:'qfmodel',is_reasoning:false},originalContent:'hello'},chatPrompt:'',imageUrls:null},is_reply:true,is_retry:false,source:1,version:'3',agent_id:'agent_common',task_id:'common',session_type:'qodercli',aliyun_user_type:'',model_config:{key:'qfmodel',display_name:'probe',model:'',format:'openai',is_vl:false,is_reasoning:false,api_key:'',url:'',source:'system',max_input_tokens:200000},custom_model:null,system:[],messages:[{role:'user',content:'hello'}],tools:[],parameters:{max_tokens:8},business:{type:'default'}});
function args(strings) { return strings.flatMap(value => {const pointer=glue.writeString(value);return [pointer,glue.lastLength()];}); }
function callString(name, strings) { return glue.callString(stack=>glue.exports[name](stack,...args(strings))); }
const authFields = callString('generate_runtime_auth_fields', [userJson]);
const auth = JSON.parse(authFields);
const userInfoJson = JSON.stringify({uid:'probe-user',encrypt_user_info:auth.encrypt_user_info,key:auth.key,organization_id:'',organization_tags:[],data_policy_agreed:false});
const context = glue.callPointer(stack=>glue.exports.qodercontext_new(stack,...args(['probe-machine','0.3.4',userInfoJson,metadataJson])));
const result = glue.callPointer(stack=>glue.exports.qodercontext_prepareInferRequest(stack,context,...args(['https://api2.qoder.sh',payloadJson,'qfmodel','system'])));
const headers = Object.fromEntries(glue.takeObject(glue.exports.requestresult_headers(result)));
function resultText(name) {
  const stack=glue.exports.__wbindgen_add_to_stack_pointer(-16);
  try {glue.exports[name](stack,result);const view=glue.view();return glue.readString(view.getInt32(stack,true),view.getInt32(stack+4,true));}
  finally {glue.exports.__wbindgen_add_to_stack_pointer(16);}
}
const url=resultText('requestresult_url');
const body=resultText('requestresult_body');
const plain=JSON.stringify({models:[{key:'probe',name:'中文 😀'}],empty:''});
const encrypted=callString('model_cache_encrypt',[plain,'probe-machine']);
const decrypted=callString('model_cache_decrypt',[encrypted,'probe-machine']);
if (plain!==decrypted) throw new Error('Node cache roundtrip failed');
await writeFile(join(outputDirectory,'reference.json'),JSON.stringify({userJson,metadataJson,payloadJson,userInfoJson,authFields,url,headers,body,plain,encrypted,decrypted,randomBytes},null,2));
glue.exports.__wbg_requestresult_free(result,0);
glue.exports.__wbg_qodercontext_free(context,0);
console.log(JSON.stringify({reference:'prepared',randomBytes,syntheticOnly:true}));
