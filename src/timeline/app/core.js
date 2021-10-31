//

function startApp() {
    window.addEventListener('load', startAppCallback);
    window.addEventListener('hashchange', startAppCallback);
}

function startAppCallback() {
    console.log('app mode: ' + window.rcrtMode);
    const path = (new URL(window.location.href)).hash;
    console.log('path = ' + path);

    const metaPromise = fetch('db/meta.json').then(resp => resp.json());
    metaPromise.then(meta => {
        const page = path.length === 0 ? 
            createFrontPage(meta) : 
            createPage(meta, path.slice(1));
        document.body.innerHTML = '';
        document.body.appendChild(page);
        document.title = path.length === 0 ? 'Rigid Timeline' : '(WIP)';
    });
}

function createPage(meta, identifier) {
    switch (meta[identifier].type) {
        case 'link':
            return createLinkPage(meta, identifier);
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

function createFrontPage(meta) {
    //
    return new Text('This is main page');
}
