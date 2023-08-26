//
//  MultiPaneView.swift
//  iosApp
//
//  Created by Arkadii Ivanov on 26/08/2023.
//  Copyright © 2023 orgName. All rights reserved.
//

import SwiftUI
import ConfettiKit

struct MultiPaneView: View {
    private let component: MultiPaneComponent
    
    @StateValue
    private var sessionDetails: ChildSlot<AnyObject, SessionDetailsComponent>
    
    init(_ component: MultiPaneComponent) {
        self.component = component
        _sessionDetails = StateValue(component.sessionDetails)
    }
    
    var body: some View {
        GeometryReader { geometry in
            HStack {
                SessionsView(component.sessions)
                    .frame(width: geometry.size.width * 0.3)
                
                let sessionDetailsComponent = sessionDetails.child?.instance
                if sessionDetailsComponent != nil {
                    SessionDetailsView(sessionDetailsComponent!)
                        .frame(width: geometry.size.width * 0.7)
                }
            }
        }
    }
}
