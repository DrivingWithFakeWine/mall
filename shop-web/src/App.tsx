import Login from './pages/Login.tsx'
import { BrowserRouter, Navigate, Routes, Route } from 'react-router-dom'
import Register from './pages/Register.tsx'
import Home from './pages/Home.tsx'
import ProductDetailPage from './pages/ProductDetail.tsx'
import CartPage from './pages/Cart.tsx'
import CheckoutPage from './pages/Checkout.tsx'
import OrderDetailPage from './pages/OrderDetail.tsx'
import OrdersPage from './pages/Orders.tsx'

function App() {
  return (
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Navigate to="/home" replace />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/home" element={<Home />} />
          <Route path="/product/:id" element={<ProductDetailPage />} />
          <Route path="/cart" element={<CartPage />} />
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/order/:id" element={<OrderDetailPage />} />
          <Route path="/orders" element={<OrdersPage />} />
        </Routes>
      </BrowserRouter>
  )
}

export default App
