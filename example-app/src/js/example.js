import { InAppBrowser } from 'capacitor-iab';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    InAppBrowser.echo({ value: inputValue })
}
