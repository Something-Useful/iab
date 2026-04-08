import Foundation
import Capacitor
import UIKit
import WebKit

@objc(InAppBrowserPlugin)
public class InAppBrowserPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "InAppBrowserPlugin"
    public let jsName = "InAppBrowser"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "open", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "close", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "executeScript", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "insertCSS", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addUserScript", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeAllUserScripts", returnType: CAPPluginReturnPromise)
    ]

    private var browserVC: InAppBrowserViewController?

    // MARK: - Helpers

    private func withBrowser(_ call: CAPPluginCall, _ body: @escaping (InAppBrowserViewController) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let vc = self?.browserVC else {
                call.reject("No browser open")
                return
            }
            body(vc)
        }
    }

    private func dismissBrowser(animated: Bool, completion: (() -> Void)? = nil) {
        guard let vc = browserVC else {
            completion?()
            return
        }
        vc.cleanup()
        vc.dismiss(animated: animated) { [weak self] in
            self?.browserVC = nil
            self?.notifyListeners("exit", data: [:])
            completion?()
        }
    }

    // MARK: - Plugin Methods

    @objc func open(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url"),
              let url = URL(string: urlString) else {
            call.reject("Invalid or missing 'url'")
            return
        }

        let toolbarColor: UIColor
        if let hex = call.getString("toolbarColor") {
            toolbarColor = UIColor(hex: hex) ?? .systemBackground
        } else {
            toolbarColor = .systemBackground
        }

        var headers: [String: String]?
        if let obj = call.getObject("headers") {
            headers = obj.compactMapValues { $0 as? String }
        }

        let title = call.getString("title")
        let closeButtonText = call.getString("closeButtonText") ?? "Done"
        let showNav = call.getBool("showNavigationButtons") ?? true
        let showUrl = call.getBool("showUrlBar") ?? true

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            let present = { [weak self] in
                guard let self else { return }
                let vc = InAppBrowserViewController(
                    url: url,
                    headers: headers,
                    toolbarColor: toolbarColor,
                    title: title,
                    closeButtonText: closeButtonText,
                    showNavigationButtons: showNav,
                    showUrlBar: showUrl
                )
                vc.delegate = self
                self.browserVC = vc
                self.bridge?.viewController?.present(vc, animated: true) {
                    call.resolve()
                }
            }

            if self.browserVC != nil {
                self.dismissBrowser(animated: false) {
                    present()
                }
            } else {
                present()
            }
        }
    }

    @objc func close(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.resolve()
                return
            }
            guard self.browserVC != nil else {
                call.resolve()
                return
            }
            self.dismissBrowser(animated: true) {
                call.resolve()
            }
        }
    }

    @objc func executeScript(_ call: CAPPluginCall) {
        guard let code = call.getString("code") else {
            call.reject("Missing 'code'")
            return
        }
        withBrowser(call) { vc in
            vc.executeScript(code) { result in
                switch result {
                case .success(let value):
                    call.resolve(["result": value])
                case .failure(let error):
                    call.reject(error.localizedDescription)
                }
            }
        }
    }

    @objc func addUserScript(_ call: CAPPluginCall) {
        guard let code = call.getString("code") else {
            call.reject("Missing 'code'")
            return
        }
        guard let timing = call.getString("injectionTime") else {
            call.reject("Missing 'injectionTime'")
            return
        }

        let injectionTime: WKUserScriptInjectionTime
        switch timing {
        case "atDocumentStart":
            injectionTime = .atDocumentStart
        case "atDocumentEnd":
            injectionTime = .atDocumentEnd
        default:
            call.reject("Invalid injectionTime: must be 'atDocumentStart' or 'atDocumentEnd'")
            return
        }

        let forMainFrameOnly = call.getBool("forMainFrameOnly") ?? true

        withBrowser(call) { vc in
            vc.addUserScript(code, injectionTime: injectionTime, forMainFrameOnly: forMainFrameOnly)
            call.resolve()
        }
    }

    @objc func removeAllUserScripts(_ call: CAPPluginCall) {
        withBrowser(call) { vc in
            vc.removeAllUserScripts()
            call.resolve()
        }
    }

    @objc func insertCSS(_ call: CAPPluginCall) {
        guard let code = call.getString("code") else {
            call.reject("Missing 'code'")
            return
        }
        withBrowser(call) { vc in
            vc.insertCSS(code) { result in
                switch result {
                case .success:
                    call.resolve()
                case .failure(let error):
                    call.reject(error.localizedDescription)
                }
            }
        }
    }
}

// MARK: - InAppBrowserDelegate

extension InAppBrowserPlugin: InAppBrowserDelegate {

    func onLoadStart(url: String) {
        notifyListeners("loadstart", data: ["url": url])
    }

    func onLoadStop(url: String) {
        notifyListeners("loadstop", data: ["url": url])
    }

    func onLoadError(url: String, code: Int, message: String) {
        notifyListeners("loaderror", data: ["url": url, "code": code, "message": message])
    }

    func onCloseRequested() {
        dismissBrowser(animated: true)
    }

    func onMessage(data: Any) {
        notifyListeners("message", data: ["data": data])
    }
}

// MARK: - UIColor hex

extension UIColor {
    convenience init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }

        var rgb: UInt64 = 0
        guard Scanner(string: s).scanHexInt64(&rgb) else { return nil }

        switch s.count {
        case 6:
            self.init(
                red: CGFloat((rgb >> 16) & 0xFF) / 255,
                green: CGFloat((rgb >> 8) & 0xFF) / 255,
                blue: CGFloat(rgb & 0xFF) / 255,
                alpha: 1
            )
        case 8:
            self.init(
                red: CGFloat((rgb >> 24) & 0xFF) / 255,
                green: CGFloat((rgb >> 16) & 0xFF) / 255,
                blue: CGFloat((rgb >> 8) & 0xFF) / 255,
                alpha: CGFloat(rgb & 0xFF) / 255
            )
        default:
            return nil
        }
    }
}
