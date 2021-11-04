//

function startApp() {
    console.log('app mode: ' + window.rcrtMode);
    window.addEventListener('load', startAppCallback);
    window.addEventListener('hashchange', startAppCallback);
}

function startAppCallback() {
    const path = (new URL(window.location.href)).hash;
    fetch('db/meta.json').then(resp => resp.json()).then(meta => {
        const page = 
            path === "" ? 
                createFrontPage(meta) : 
            path.startsWith('#_hidden/') ?
                createHiddenPage(meta, path.slice(9)) :
                createPage(meta, path.slice(1));
        document.body.innerHTML = '';
        document.body.appendChild(page);
        document.title = 'Rigid Timeline';  // TODO
    });
}

const FONT_SANS = 'Noto Sans CJK SC';
const FONT_MONO = 'Noto Sans Mono';

function addPageStyle(node) {
    node.style.display = 'block';
    node.style.margin = '0 auto';
    node.style.maxWidth = '800px';
}

function addParagraphStyle(node) {
    node.style.fontFamily = FONT_SANS;
    node.style.fontSize = '1.2em';
}

function addAttachingStyle(node) {
    node.style.marginTop = '-1em';
}

function addSeriesStyle(node) {
    node.style.fontFamily = FONT_SANS;
    node.style.fontSize = '0.9em';
}

function addTimeStyle(node) {
    node.style.fontFamily = FONT_SANS;
    node.style.fontSize = '0.9em';
}

function addTitleStyle(node) {
    node.style.fontFamily = FONT_SANS;
    node.style.fontSize = '1.2em';
}

function addRefStyle(node) {
    node.style.fontFamily = FONT_MONO;
    node.style.fontSize = '0.85em';
}

function addLinkStyle(node) {
    node.style.fontFamily = FONT_SANS;
    node.style.fontSize = '0.95em';
    node.style.color = 'black';
}

function getTimeString(time) {
    return (new Date(time * 1000)).toLocaleString('zh', {
        hour12: false, dateStyle: 'long',  timeStyle: 'short',
    });
}

function createFrontPage(meta) {
    const testFontNode = document.createElement('div');
    document.body.appendChild(testFontNode);
    testFontNode.style.fontFamily = FONT_SANS;
    const actualFont = window.getComputedStyle(testFontNode).fontFamily;
    testFontNode.style.fontFamily = FONT_MONO;
    const actualMonoFont = window.getComputedStyle(testFontNode).fontFamily;
    document.body.removeChild(testFontNode);
    if (!FONT_SANS.includes(actualFont) || !FONT_MONO.includes(actualMonoFont)) {
        console.log('TODO prompt install fonts');
    }

    return new Text('This is main page');
}

function createPage(meta, identifier) {
    if (meta[identifier] === undefined) {
        return create404Page(meta);
    }
    switch (meta[identifier].type) {
        case 'link':
            return createLinkPage(meta, identifier);
        case 'post':
            return createPostPage(meta, identifier);
        case 'article':
            return createArticlePage(meta, identifier);
        default:
            console.warn('unknown entry type: ' + meta[identifier].type);
            return new Text('Oops...');
    }
}

function create404Page(meta) {
    return new Text('404 NOT FOUND');
}

function createLinkPage(meta, identifier) {
    const node = document.createElement('a');
    addPageStyle(node);
    addLinkStyle(node);
    node.href = node.text = meta[identifier].url;
    return node;
}

function createPostPage(meta, identifier) {
    const node = document.createElement('div');
    addPageStyle(node);
    const time = document.createElement('p');
    addTimeStyle(time);
    time.innerText = getTimeString(meta[identifier].time);
    node.appendChild(time);
    const content = document.createElement('p');
    addParagraphStyle(content);
    content.innerText = meta[identifier].text;
    node.appendChild(content);
    return node;
}

function createArticlePage(meta, identifier) {
    const node = document.createElement('div');
    addPageStyle(node);
    const title = document.createElement('h1');
    title.innerText = meta[identifier].title;
    addTitleStyle(title);
    node.appendChild(title);
    if (meta[identifier].series !== "") {
        const series = document.createElement('p');
        addSeriesStyle(series);
        addAttachingStyle(series);
        series.appendChild(new Text('系列 / '));
        const seriesA = document.createElement('a');
        addLinkStyle(seriesA);
        seriesA.innerText = meta[identifier].series;
        seriesA.href = '#';  // TODO
        series.appendChild(seriesA);
        node.appendChild(series);
    }
    const time = document.createElement('p');
    addTimeStyle(time);
    addAttachingStyle(time);
    time.innerText = getTimeString(meta[identifier].time);
    node.appendChild(time);
    fetch(`db/${identifier}.txt`).then(resp => resp.text()).then(content => {
        for (let paragraph of content.split('\n\n')) {
            if (paragraph === "") {
                continue;
            }
            const p = document.createElement('p');
            addParagraphStyle(p);
            for (let match of paragraph.matchAll(/(.*?)((\[.*?\]#\w{6})|$)/g)) {
                const [text, ref] = [match[1], match[2]];
                p.appendChild(new Text(text));
                if (ref !== "") {
                    const a = document.createElement('a');
                    addLinkStyle(a);
                    a.appendChild(new Text(ref.slice(0, ref.length - 7)));
                    const aRef = document.createElement('span');
                    aRef.innerText = ref.slice(ref.length - 7);
                    addRefStyle(aRef);
                    a.appendChild(aRef);
                    a.href = ref.slice(ref.length - 7);
                    p.appendChild(a);
                }
            }
            node.appendChild(p);
        }
    });
    return node;
}

function createHiddenPage(meta, page) {
    switch (page) {
        case 'list':
            return createListPage(meta);
        default:
            console.warn('unknown hidden page: ' + page);
            return new Text('Oops (hidden)...');
    }
}

function createListPage(meta) {
    const node = document.createElement('ul');
    for (let identifier of Object.keys(meta).sort()) {
        const a = document.createElement('a');
        a.innerText = '#' + identifier + ' ' + meta[identifier].type;
        a.href = '#' + identifier;
        a.style.display = 'block';
        node.appendChild(a);
    }
    return node;
}
