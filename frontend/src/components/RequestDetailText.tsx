import { javaProxyText } from '../i18n/javaProxy';
import { memo, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

const PREVIEW_LENGTH = 10_000;
const ROW_LENGTH = 120;
const ROW_HEIGHT = 22;
const OVERSCAN = 6;

// Bound both the number of DOM nodes and the length of a single unbroken line.
// Store offsets instead of copying the entire body into an array of strings.
function rowOffsets(text: string) {
  const rows: number[] = [0];
  let start = 0;
  while (start < text.length) {
    let end = Math.min(start + ROW_LENGTH, text.length);
    const newline = text.slice(start, end).indexOf('\n');
    if (newline >= 0) end = start + newline + 1;
    else if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--;
    rows.push(end);
    start = end;
  }
  return rows;
}

function VirtualText({ text, title }: { text: string; title: string }) {
  const offsets = useMemo(() => rowOffsets(text), [text]);
  const viewport = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [height, setHeight] = useState(360);
  useEffect(() => {
    const element = viewport.current!;
    const observer = new ResizeObserver(() => setHeight(element.clientHeight));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const count = offsets.length - 1;
  // Chromium caps element heights; scale the scrollbar for newline-heavy captures.
  const totalHeight = Math.min(count * ROW_HEIGHT, 8_000_000);
  const logicalTop = scrollTop * Math.max(1, (count * ROW_HEIGHT - height) / Math.max(1, totalHeight - height));
  const first = Math.max(0, Math.floor(logicalTop / ROW_HEIGHT) - OVERSCAN);
  const last = Math.min(count, Math.ceil((logicalTop + height) / ROW_HEIGHT) + OVERSCAN);
  const rows = [];
  for (let index = first; index < last; index++) {
    rows.push(<div className="proxy-virtual-row" key={index} style={{ top: index * ROW_HEIGHT - logicalTop + scrollTop }}>
      {text.slice(offsets[index], offsets[index + 1]).replace(/\r?\n$/, '')}
    </div>);
  }
  return <div className="proxy-virtual-text" ref={viewport} tabIndex={0} role="region" aria-label={title}
    onScroll={event => { event.stopPropagation(); setScrollTop(event.currentTarget.scrollTop); }}>
    <div className="proxy-virtual-spacer" style={{ height: totalHeight }}>{rows}</div>
  </div>;
}

export const RequestDetailText = memo(function RequestDetailText({ text, title, zh, renderFormatted }: {
  text: string; title: string; zh: boolean; renderFormatted?: (text: string) => ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  const large = text.length > PREVIEW_LENGTH;
  const preview = large ? text.slice(0, PREVIEW_LENGTH) : text;
  return <>
    {large && <div className="proxy-body-preview-controls">
      <span>{expanded ? (zh ? '已展开全文；复制可获取完整内容。' : 'Full text expanded. Copy includes the full content.')
        : (zh ? '正文较长，默认显示前 10,000 个字符；复制可获取完整内容。' : 'Showing the first 10,000 characters. Copy includes the full content.')}</span>
      <button type="button" className="secondary-button" aria-expanded={expanded}
        onClick={() => setExpanded(value => !value)}>
        {expanded ? (zh ? '收起' : 'Collapse') : (zh ? '展开全文' : 'Expand full text')}
      </button>
    </div>}
    {large && expanded ? <>
      <p className="proxy-body-preview-hint">{javaProxyText('fullTextHint', zh ? 'zh-CN' : 'en')}</p>
      <VirtualText text={text} title={title} />
    </> : renderFormatted ? renderFormatted(text) : <pre tabIndex={0}>{preview || (zh ? '（空）' : '(empty)')}</pre>}
  </>;
});
