import { copyFile, mkdir, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const source = fileURLToPath(new URL('../../target/java-llm-proxy-1.0.0.jar', import.meta.url));
const destination = fileURLToPath(new URL('../src-tauri/resources/java-llm-proxy.jar', import.meta.url));
try {
  await access(source);
} catch {
  throw new Error('Build the Java backend first: mvn package -DskipTests -Dgpg.skip -Pproduction');
}
await mkdir(fileURLToPath(new URL('../src-tauri/resources/', import.meta.url)), { recursive: true });
await copyFile(source, destination);
console.log('Java kernel prepared for Tauri.');
