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
            path.length === 0 ? 
                createFrontPage(meta) : 
            path.startsWith('#_hidden/') ?
                createHiddenPage(meta, path.slice(9)) :
                createPage(meta, path.slice(1));
        document.body.innerHTML = '';
        document.body.appendChild(page);
        document.title = path.length === 0 ? 'Rigid Timeline' : '(WIP)';
    });
}

function addPageStyle(node) {
    node.style.margin = '0 auto';
    node.style.maxWidth = '800px';
}

const FONT_SANS = 'Noto Sans CJK SC';

function addParagraphStyle(node) {
    node.style.fontFamily = FONT_SANS;
    node.style.fontSize = '1.2em';
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
    node.style.fontFamily = FONT_SANS;
    node.style.fontSize = '0.95em';
}

function getTimeString(time) {
    return (new Date(time * 1000)).toLocaleString('zh', {
        hour12: false, dateStyle: 'long',  timeStyle: 'short',
    });
}

function createFrontPage(meta) {
    //
    return new Text('This is main page');
}

function createPage(meta, identifier) {
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

function createLinkPage(meta, identifier) {
    const node = document.createElement('a');
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
    const time = document.createElement('p');
    addTimeStyle(time);
    time.innerText = getTimeString(meta[identifier].time);
    node.appendChild(time);
    fetch(`db/${identifier}.txt`).then(resp => resp.text()).then(content => {
        for (let paragraph of content.split('\n\n')) {
            if (paragraph.length === 0) {
                continue;
            }
            const p = document.createElement('p');
            addParagraphStyle(p);
            for (let match of paragraph.matchAll(/(.*?)((\[.*?\]#\w{6})|$)/g)) {
                const [text, ref] = [match[1], match[2]];
                p.appendChild(new Text(text));
                if (ref.length !== 0) {
                    const a = document.createElement('a');
                    addRefStyle(a);
                    a.innerText = ref;
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
