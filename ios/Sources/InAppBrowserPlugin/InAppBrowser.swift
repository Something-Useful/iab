import UIKit
import WebKit

// MARK: - Delegate Protocol

protocol InAppBrowserDelegate: AnyObject {
    func onLoadStart(url: String)
    func onLoadStop(url: String)
    func onLoadError(url: String, code: Int, message: String)
    func onCloseRequested()
    func onMessage(data: Any)
}

// MARK: - InAppBrowserViewController

class InAppBrowserViewController: UIViewController {

    weak var delegate: InAppBrowserDelegate?
    private(set) var webView: WKWebView!

    private var toolbar: UIView!
    private var titleLabel: UILabel!
    private var urlLabel: UILabel!
    private var closeButton: UIButton!
    private var backButton: UIButton?
    private var forwardButton: UIButton?
    private var spinner: UIActivityIndicatorView!

    private let initialURL: URL
    private let headers: [String: String]?
    private let toolbarColor: UIColor
    private let titleText: String?
    private let closeButtonText: String
    private let showNavigationButtons: Bool
    private let showUrlBar: Bool

    static let messageBridgeName = "iab"

    init(
        url: URL,
        headers: [String: String]? = nil,
        toolbarColor: UIColor = .systemBackground,
        title: String? = nil,
        closeButtonText: String = "Done",
        showNavigationButtons: Bool = true,
        showUrlBar: Bool = true
    ) {
        self.initialURL = url
        self.headers = headers
        self.toolbarColor = toolbarColor
        self.titleText = title
        self.closeButtonText = closeButtonText
        self.showNavigationButtons = showNavigationButtons
        self.showUrlBar = showUrlBar
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        setupToolbar()
        setupWebView()
        setupSpinner()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if webView.url == nil {
            loadInitialURL()
        }
    }

    // MARK: - Setup

    private func setupWebView() {
        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: Self.messageBridgeName)
        config.allowsInlineMediaPlayback = true
        if #available(iOS 14.5, *) {
            config.upgradeKnownHostsToHTTPS = true
        }

        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.allowsBackForwardNavigationGestures = true

        #if DEBUG
        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }
        #endif

        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: toolbar.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    private func setupToolbar() {
        toolbar = UIView()
        toolbar.translatesAutoresizingMaskIntoConstraints = false
        toolbar.backgroundColor = toolbarColor
        view.addSubview(toolbar)

        // Close button
        closeButton = UIButton(type: .system)
        closeButton.setTitle(closeButtonText, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        toolbar.addSubview(closeButton)

        // Title label
        titleLabel = UILabel()
        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.textAlignment = .center
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        toolbar.addSubview(titleLabel)

        // URL label
        urlLabel = UILabel()
        urlLabel.font = .systemFont(ofSize: 12)
        urlLabel.textColor = .secondaryLabel
        urlLabel.textAlignment = .center
        urlLabel.lineBreakMode = .byTruncatingMiddle
        urlLabel.translatesAutoresizingMaskIntoConstraints = false
        urlLabel.isHidden = !showUrlBar
        toolbar.addSubview(urlLabel)

        // Separator
        let separator = UIView()
        separator.backgroundColor = .separator
        separator.translatesAutoresizingMaskIntoConstraints = false
        toolbar.addSubview(separator)

        // Toolbar frame: extends behind status bar
        let contentHeight: CGFloat = showUrlBar ? 64 : 44
        var constraints: [NSLayoutConstraint] = [
            toolbar.topAnchor.constraint(equalTo: view.topAnchor),
            toolbar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            toolbar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            toolbar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: contentHeight),

            closeButton.leadingAnchor.constraint(equalTo: toolbar.leadingAnchor, constant: 16),
            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 10),

            separator.leadingAnchor.constraint(equalTo: toolbar.leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: toolbar.trailingAnchor),
            separator.bottomAnchor.constraint(equalTo: toolbar.bottomAnchor),
            separator.heightAnchor.constraint(equalToConstant: 1.0 / UIScreen.main.scale)
        ]

        // Navigation buttons
        if showNavigationButtons {
            let back = UIButton(type: .system)
            back.setImage(UIImage(systemName: "chevron.left"), for: .normal)
            back.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
            back.translatesAutoresizingMaskIntoConstraints = false
            back.isEnabled = false
            toolbar.addSubview(back)
            backButton = back

            let forward = UIButton(type: .system)
            forward.setImage(UIImage(systemName: "chevron.right"), for: .normal)
            forward.addTarget(self, action: #selector(forwardTapped), for: .touchUpInside)
            forward.translatesAutoresizingMaskIntoConstraints = false
            forward.isEnabled = false
            toolbar.addSubview(forward)
            forwardButton = forward

            constraints += [
                back.trailingAnchor.constraint(equalTo: forward.leadingAnchor, constant: -16),
                back.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor),
                forward.trailingAnchor.constraint(equalTo: toolbar.trailingAnchor, constant: -16),
                forward.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor)
            ]
        }

        // Title + URL positioning
        let navTrailing = forwardButton?.leadingAnchor ?? toolbar.trailingAnchor
        let navTrailingConstant: CGFloat = (forwardButton != nil) ? -8 : -16

        if showUrlBar {
            constraints += [
                titleLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
                titleLabel.leadingAnchor.constraint(greaterThanOrEqualTo: closeButton.trailingAnchor, constant: 8),
                titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: navTrailing, constant: navTrailingConstant),
                titleLabel.centerXAnchor.constraint(equalTo: toolbar.centerXAnchor),

                urlLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 2),
                urlLabel.leadingAnchor.constraint(greaterThanOrEqualTo: closeButton.trailingAnchor, constant: 8),
                urlLabel.trailingAnchor.constraint(lessThanOrEqualTo: navTrailing, constant: navTrailingConstant),
                urlLabel.centerXAnchor.constraint(equalTo: toolbar.centerXAnchor)
            ]
        } else {
            constraints += [
                titleLabel.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor),
                titleLabel.leadingAnchor.constraint(greaterThanOrEqualTo: closeButton.trailingAnchor, constant: 8),
                titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: navTrailing, constant: navTrailingConstant),
                titleLabel.centerXAnchor.constraint(equalTo: toolbar.centerXAnchor)
            ]
        }

        NSLayoutConstraint.activate(constraints)
    }

    private func setupSpinner() {
        spinner = UIActivityIndicatorView(style: .large)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.hidesWhenStopped = true
        view.addSubview(spinner)
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: webView.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: webView.centerYAnchor)
        ])
    }

    // MARK: - Loading

    private func loadInitialURL() {
        var request = URLRequest(url: initialURL)
        headers?.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        webView.load(request)
    }

    private func updateUI() {
        titleLabel.text = titleText ?? webView.title ?? ""
        if showUrlBar {
            urlLabel.text = webView.url?.host ?? ""
        }
        backButton?.isEnabled = webView.canGoBack
        forwardButton?.isEnabled = webView.canGoForward
    }

    // MARK: - Actions

    @objc private func closeTapped() {
        delegate?.onCloseRequested()
    }

    @objc private func backTapped() {
        webView.goBack()
    }

    @objc private func forwardTapped() {
        webView.goForward()
    }

    // MARK: - Public API

    func cleanup() {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: Self.messageBridgeName)
        webView.stopLoading()
        webView.navigationDelegate = nil
    }

    func executeScript(_ code: String, completion: @escaping (Result<String, Error>) -> Void) {
        webView.callAsyncJavaScript(code, arguments: [:], in: nil, in: .page) { result in
            switch result {
            case .success(let value):
                if value is NSNull || value is () {
                    completion(.success(""))
                } else if let data = try? JSONSerialization.data(withJSONObject: value),
                          let json = String(data: data, encoding: .utf8) {
                    completion(.success(json))
                } else {
                    completion(.success(String(describing: value)))
                }
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    func addUserScript(_ code: String, injectionTime: WKUserScriptInjectionTime, forMainFrameOnly: Bool) {
        let script = WKUserScript(source: code, injectionTime: injectionTime, forMainFrameOnly: forMainFrameOnly)
        webView.configuration.userContentController.addUserScript(script)
    }

    func removeAllUserScripts() {
        webView.configuration.userContentController.removeAllUserScripts()
    }

    func insertCSS(_ code: String, completion: @escaping (Result<Void, Error>) -> Void) {
        let escaped = code
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "`", with: "\\`")
            .replacingOccurrences(of: "${", with: "\\${")
        let script = "(function(){var s=document.createElement('style');s.textContent=`\(escaped)`;document.head.appendChild(s);})()"
        webView.evaluateJavaScript(script) { _, error in
            if let error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
}

// MARK: - WKNavigationDelegate

extension InAppBrowserViewController: WKNavigationDelegate {

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        spinner.startAnimating()
        delegate?.onLoadStart(url: webView.url?.absoluteString ?? "")
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        spinner.stopAnimating()
        updateUI()
        delegate?.onLoadStop(url: webView.url?.absoluteString ?? "")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handleNavigationError(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handleNavigationError(error)
    }

    private func handleNavigationError(_ error: Error) {
        spinner.stopAnimating()
        let nsError = error as NSError
        delegate?.onLoadError(url: webView.url?.absoluteString ?? "", code: nsError.code, message: nsError.localizedDescription)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }

        let scheme = url.scheme?.lowercased() ?? ""
        if ["tel", "mailto", "sms", "itms-appss", "itms-apps"].contains(scheme) {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }

        // Handle target=_blank links by loading in the same webview
        if navigationAction.targetFrame == nil {
            webView.load(navigationAction.request)
            decisionHandler(.cancel)
            return
        }

        decisionHandler(.allow)
    }

    func webView(
        _ webView: WKWebView,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let protectionSpace = challenge.protectionSpace

        if protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
            guard let serverTrust = protectionSpace.serverTrust else {
                completionHandler(.cancelAuthenticationChallenge, nil)
                return
            }
            // Only prompt if the certificate actually fails evaluation
            var error: CFError?
            if SecTrustEvaluateWithError(serverTrust, &error) {
                completionHandler(.performDefaultHandling, nil)
                return
            }
            let alert = UIAlertController(
                title: "Certificate Error",
                message: "The certificate for \(protectionSpace.host) is not valid. Do you want to continue?",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in
                completionHandler(.cancelAuthenticationChallenge, nil)
            })
            alert.addAction(UIAlertAction(title: "Continue", style: .destructive) { _ in
                completionHandler(.useCredential, URLCredential(trust: serverTrust))
            })
            presentSafely(alert) {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
            return
        }

        if protectionSpace.authenticationMethod == NSURLAuthenticationMethodHTTPBasic ||
            protectionSpace.authenticationMethod == NSURLAuthenticationMethodHTTPDigest {
            let alert = UIAlertController(
                title: "Authentication Required",
                message: "Log in to \(protectionSpace.host)",
                preferredStyle: .alert
            )
            alert.addTextField { $0.placeholder = "Username" }
            alert.addTextField { $0.placeholder = "Password"; $0.isSecureTextEntry = true }
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in
                completionHandler(.cancelAuthenticationChallenge, nil)
            })
            alert.addAction(UIAlertAction(title: "Log In", style: .default) { _ in
                let username = alert.textFields?[0].text ?? ""
                let password = alert.textFields?[1].text ?? ""
                completionHandler(.useCredential, URLCredential(user: username, password: password, persistence: .forSession))
            })
            presentSafely(alert) {
                completionHandler(.cancelAuthenticationChallenge, nil)
            }
            return
        }

        completionHandler(.performDefaultHandling, nil)
    }

    private func presentSafely(_ vc: UIViewController, fallback: @escaping () -> Void) {
        if presentedViewController != nil || !isViewLoaded || view.window == nil {
            fallback()
            return
        }
        present(vc, animated: true)
    }
}

// MARK: - WKUIDelegate

extension InAppBrowserViewController: WKUIDelegate {

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        webView.load(navigationAction.request)
        return nil
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptAlertPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping () -> Void
    ) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping (Bool) -> Void
    ) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }

    func webView(
        _ webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt prompt: String,
        defaultText: String?,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping (String?) -> Void
    ) {
        let alert = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
        alert.addTextField { $0.text = defaultText }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(nil) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(alert.textFields?.first?.text) })
        present(alert, animated: true)
    }
}

// MARK: - WKScriptMessageHandler

extension InAppBrowserViewController: WKScriptMessageHandler {

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == Self.messageBridgeName else { return }
        delegate?.onMessage(data: message.body)
    }
}
